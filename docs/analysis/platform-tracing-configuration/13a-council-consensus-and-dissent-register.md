# 13a — Регистр консенсуса и разногласий Model Council

## Пост-совещательный арбитраж: гибридная конфигурация трейсинга

| Поле | Значение |
|---|---|
| Роль автора | Principal Platform Architect, независимый пост-совещательный арбитр |
| Репозиторий | `E:\Platform_Traces` |
| Ветка (фактическая) | `refactor/cursor-rules` — **подтверждено** (`git branch --show-current`) |
| HEAD | `9fc01d7 platform-tracing-configuration analyzis` |
| Java (установленная) | `21.0.6+8-LTS-188`, toolchain `build.gradle:25` → `JavaLanguageVersion.of(21)` |
| Активных Gradle-модулей | **16** (`settings.gradle`, 16 директив `include`) |
| Версия продукта | `0.1.0-SNAPSHOT`, pre-production |
| Дата арбитража | 2026-07-27 |
| Изменений в production-коде | **0** (задача read-only; см. §13) |
| **Implementation GO** | **NOT_GRANTED** |
| **Legacy deletion GO** | **BLOCKED** |
| **Итоговый токен** | **`EVIDENCE_CLOSURE_REQUIRED`** |

---

## 0. Легенда доказательств и порядок приоритета

Применялась строгая иерархия:

```text
1. Текущий source репозитория, build-файлы, исполняемые тесты
2. Воспроизводимые генерируемые артефакты репозитория
3. Исправленные анализы 01–12
4. Отчёты членов Model Council
5. Существующий план
6. Внешняя документация
```

Метки:

| Метка | Значение |
|---|---|
| `FACT` | Подтверждено текущим кодом/сборкой/исполнением в этой сессии |
| `FACT_ABSENCE` | Подтверждённое отсутствие в текущем checkout |
| `INFERRED` | Логический вывод из FACT, не прямое наблюдение |
| `PLAN` | Целевое состояние; кода нет |
| `UNVERIFIED` | Утверждение источника, не проверенное кодом |
| `CONFLICT` | Источники противоречат друг другу |

Дополнительно введена метка, которой не было в исходной постановке, но которая потребовалась фактами:

| Метка | Значение |
|---|---|
| `FACT_UNTRACKED` | Артефакт физически существует в working tree, но **не находится под контролем версий** и потому невоспроизводим из checkout |

---

## 0.1 Коррекция путей входных документов (обязательна к учёту)

Три пути из постановки задачи не существуют в указанном виде. Это исправлено, а не обойдено молчанием.

| Путь из постановки | Фактическое состояние | Что использовано |
|---|---|---|
| `Analyzis_1\NN-...-corrected.md` (12 файлов) | `FACT_ABSENCE` — файлов с суффиксом `-corrected` нет | Файлы `Analyzis_1\NN-....md` **верхнего уровня** идентифицированы как исправленные версии по номеру и заголовку: подзаголовки «Corrected Repository-Grounded Report/Decision», статусы `PASS_WITH_CORRECTIONS`, `PASS_WITH_MAJOR_CORRECTIONS`, `PASS_WITH_CRITICAL_CORRECTIONS`, `PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS`, `PASS_WITH_CRITICAL_SECURITY_AMENDMENTS`, `PASS_WITH_CRITICAL_METHODOLOGY_CORRECTIONS`, `PASS_WITH_BLOCKERS` |
| `Analyzis_1\tmp\NN-....md` | `FACT` — 12 файлов, содержащих эхо исходного prompt-пакета («This prompt pack is the repository-grounded replacement…»), с повреждённой кодировкой заголовков | **Не использованы.** Это исходные неисправленные материалы; подстановка запрещена постановкой |
| `.cursor/plans/immutable_config_namespace_split_ba8da442.plan.md` | `FACT_ABSENCE` в этом пути | Использован `docs/analysis/plans/immutable_config_namespace_split_ba8da442.plan.md` — единственная копия в репозитории |

Все 15 обязательных документов (12 анализов + 3 отчёта совета) и все 5 репозиторных источников доказательств прочитаны полностью. Приостановки выполнения не потребовалось.

---

## 1. Исполнительный вердикт

### 1.1 Что решено

Целевое направление архитектуры **принимается** и не фальсифицировано ни одним фактом репозитория:

```text
Agent-first production topology
  -> одно глубоко иммутабельное принятое состояние политики, владелец — Agent-extension
  -> одна агрегатная граница записи через приватный process-local MBeanServer
  -> Config Server как production-авторитет желаемого состояния
  -> DEV_ACTUATOR как non-production JMX-only канал
  -> платформенный MBeanServer read-only после cutover
```

### 1.2 Почему implementation GO не выдаётся

Совет израсходовал существенный исследовательский бюджет и закрыл все 22 нормативных решения прозой. Это **не является** основанием для GO. Пять обязательных доказательных гейтов остаются открытыми, и — что важнее — **прямая инспекция кода в этой сессии опровергла или скорректировала утверждения всех трёх членов совета** по четырём из шести спорных вопросов.

Ключевой вывод арбитража: **разногласия совета по C-01 и C-06 были разрешимы кодом и JDK-спайком, но ни один член совета не выполнил ни того, ни другого.** Fable и GPT-5.6 спорили о выразительности generic API, не приведя фактическую сигнатуру `tryUpdate`; Gemini и GPT-5.6 спорили о `newMBeanServer` против `createMBeanServer`, не запустив ни одного процесса. Оба вопроса закрыты ниже исполняемыми доказательствами.

### 1.3 Что изменилось по сравнению с любым отчётом совета

| # | Установленный в этой сессии факт | Последствие |
|---|---|---|
| 1 | Артефакты Step 0B **существуют в working tree, но не закоммичены** | Ни «ABSENT» (Fable, GPT), ни «confirmed» (Gemini) не верны. Новая категория: `PRESENT_BUT_UNTRACKED` |
| 2 | `tryUpdate` — **неограниченный внутренний retry-loop**, возвращающий bare `boolean` | Классификация `APPLIED\|UNCHANGED\|REJECTED\|STALE\|FAILED` **невыразима** текущим API. Gate E3 **не закрыт**; заявление Fable «E3 закрыт» — MODEL_ERROR |
| 3 | Точное число различных config-имён `platform.tracing.*` = **78** | И «64», и «67» неверны: оба игнорируют `PropagationDefaults` и `PlatformResourceProvider` целиком |
| 4 | `findMBeanServer(String)` сопоставляет **`MBeanServerId`, а не default domain**; платформенный сервер **тоже** в списке фабрики; дубликаты домена **разрешены** | Формулировка «Spring находит ровно один» требует marker-MBean и прикладного enforcement. Ни один член совета этого не отметил |
| 5 | Приватный сервер **невидим** удалённому коннектору; платформенный write-op **удалённо вызываем и успешно выполнен** | Обход безопасности из отчёта 07 §11.1 — теперь исполняемый `FACT`, а не гипотеза |
| 6 | Публикуемый BOM транслирует `spring-cloud-context:4.1.5` как **`api`**-констрейнт; 4.1.x = поезд 2023.0.x = **EOL** и Boot 3.2/3.3 | Дефект существует **независимо** от решения по Config Server и затрагивает ~80 сервисов-потребителей. Не идентифицирован ни одним членом совета |

### 1.4 Формулировка вердикта

```text
Направление архитектуры:      ПРИНЯТО
Модель авторитета/namespace:  ПРИНЯТА на уровне префиксов
Государство доказательств:    НЕ ЗАКРЫТО
Implementation GO:            NOT_GRANTED
Legacy deletion GO:           BLOCKED
Итоговый токен:               EVIDENCE_CLOSURE_REQUIRED
```

Настоящий документ **не** утверждает наличие implementation GO ни в какой форме.

---

## 2. Коррекции целостности доказательств

### 2.1 EI-01 — Артефакты Step 0B: `PRESENT_BUT_UNTRACKED` (разрешение C-02)

Это самая значимая коррекция. Постановка задачи предлагала три исхода; фактическое состояние — четвёртый.

**Что существует (`FACT_UNTRACKED`):**

| Артефакт | Доказательство |
|---|---|
| Source set `configProvenance` | `platform-tracing-e2e-tests/build.gradle:58-65` — седьмой из семи source set'ов |
| Task `configProvenanceTest` | `platform-tracing-e2e-tests/build.gradle:325-337`, тип `Test`, `testClassesDirs = sourceSets.configProvenance.output.classesDirs` |
| Зависимости Spring Cloud | `build.gradle:187-194`: `enforcedPlatform('org.springframework.boot:spring-boot-dependencies:3.5.6')`, `enforcedPlatform('org.springframework.cloud:spring-cloud-dependencies:2025.0.0')`, `spring-cloud-starter-config`, `spring-cloud-config-server` |
| 8 Java-файлов | `platform-tracing-e2e-tests/src/configProvenance/java/.../configserver/` — `ConfigServerProvenanceCharacterizationTest.java` (18 879 б), `ConfigServerGitProcessCharacterizationTest.java` (9 622 б), `ConfigServerProcessApplication.java`, `ConfigServerTestApplication.java`, `support/ProvenanceInspector.java`, `support/ConfigServerResponseStub.java`, `support/EvidenceRecorder.java`, `support/ConfigClientHarness.java`; mtime 2026-07-26 |
| T1–T10 как методы | Ровно 10 `@Test` в `ConfigServerProvenanceCharacterizationTest`: `t1_pureRemotePolicyHasTrustedEffectiveAndDesiredViews:35`, `t2_commandLineWinsWhileLocalYamlPrecedenceIsCharacterized:56`, `t3_refreshRemovalRebuildsFullSnapshotAndSelectsExplicitDefault:105`, `t4_equalStructuralBootstrapAndRemoteValuesDoNotRequireRestart:131`, `t5_differentStructuralDesiredValueIsVisibleAndRequiresRestart:153`, `t6_systemPolicyOverrideIsDetectedAndFailsClosed:175`, `t7_successfulFetchWithoutVersionKeepsRevisionOptional:197`, `t8_directMarkerReadDefeatsEffectiveVersionSpoofing:216`, `t9_removedPropertyAppearsInDiffAndDisappearsFromBothViews:235`, `t10_listenerSeesUpdatedEnvironmentSourcesAndMarker:263` |
| Layer B | `ConfigServerGitProcessCharacterizationTest.realConfigServerProcessReadsGitRevisionAndRefreshesAfterCommit:37` — `git init/add/commit/rev-parse` в `@TempDir`, форк дочерней JVM, polling готовности с границей 45 с, требует `git` в `PATH`, Docker не требуется |
| Gradle видит source set | `:platform-tracing-e2e-tests:tasks --all` → `BUILD SUCCESSFUL`; сгенерированы `configProvenanceTest`, `configProvenanceClasses`, `compileConfigProvenanceJava`, `processConfigProvenanceResources` |
| Заявленный стек — истинный | Разрешение `configProvenanceRuntimeClasspath` даёт ровно: `spring-boot:3.5.6`, `spring-cloud-config-client:4.3.0`, `spring-cloud-context:4.3.0`, `spring-cloud-config-server:4.3.0`, `org.eclipse.jgit:6.10.1.202505221210-r`. Это **единственная** часть заявления Step 0B, которая сегодня является `REPRODUCIBLE_FACT` |

**Что отсутствует (`FACT_ABSENCE`):**

| Пробел | Доказательство |
|---|---|
| Контроль версий | `git status --short` → ` M platform-tracing-e2e-tests/build.gradle` и `?? platform-tracing-e2e-tests/src/configProvenance/`. `git ls-files platform-tracing-e2e-tests/src \| Select-String configProvenance` → **пусто, ни одного отслеживаемого файла**. `git show HEAD:platform-tracing-e2e-tests/build.gradle` не содержит ни `configProvenance`, ни `spring-cloud`. `git diff` — 32 вставки, 0 удалений. `.gitignore` путь не исключает — файлы просто никогда не добавлялись |
| Артефакты исполнения | Каталог `build/` модуля пуст: нет `test-results/configProvenanceTest`, нет скомпилированных классов, нет `build/reports/config-provenance/evidence.txt`, который сам же task пишет через system property `config.provenance.evidence.file` |
| Включение в `check`/`build` | `configProvenanceTest` **не подключён** к `check`/`build`. При этом, в отличие от task `test`, он **не имеет** guard'а `onlyIf { project.hasProperty('runE2e') }`, то есть исполняется при явном вызове, но CI не вызовет его никогда |

**Асимметрия, которая делает это блокирующим:** документы, утверждающие «Step 0B PASS, COMPLETED, 2026-07-26» — `docs/research/config-server-property-provenance-spike.md` и `docs/research/hybrid-tracing-configuration-codebase-context.md` — **закоммичены и чисты**. Рабочее дерево содержит ровно две записи-исключения, обе относящиеся к доказательству. Ревьюер, забравший `refactor/cursor-rules`, получает утверждения без доказательства, причём документы ссылаются на `platform-tracing-e2e-tests/build.gradle` как на источник FACT-строки, а этот файл на HEAD такого содержимого не имеет.

**Вердикт C-02:** `PRESENT_BUT_UNTRACKED`. Функционально ближе к `REPRODUCIBLE_FACT`, чем полагали Fable и GPT-5.6; юридически ближе к `HISTORICAL_CLAIM_NOT_IN_CHECKOUT`, чем полагал Gemini.

**Какие прежние архитектурные выводы остаются допущениями до прохождения S-02:**

| Вывод | Кто на него опирался | Статус до S-02 |
|---|---|---|
| `EnvironmentChangeEvent` доставляет полный обновлённый Environment (T10) | Отчёт 12 A-04 «ATTACK REJECTED»; Gemini D12; GPT D12; Fable D-12 | `UNVERIFIED` — отклонение атаки A-04 держится на невоспроизводимом тесте |
| Удалённые ключи присутствуют в `getKeys()` со значением `null` (T9) | Отчёт 12 §8; правило «changedKeys только триггер» | `UNVERIFIED` |
| Per-key provenance разрешима через `PropertySource` name/type + corroborating origin | Отчёты 01 §2.5, 05 §4 | `UNVERIFIED` |
| Revision читается напрямую из marker-source `configClient`, спуфинг через `Environment.getProperty` не проходит (T8) | Отчёт 01 §2.5; GPT §4.1 | `UNVERIFIED` |
| Локальный override policy-ключа в режиме CONFIG_SERVER детектируется и fail-closed (T6) | Отчёты 05, 08; все три члена совета | `UNVERIFIED` |
| Boot 3.5.6 / Cloud 2025.0.0 / Config Client 4.3.0 разрешается совместно | — | **`FACT`** — единственный воспроизведённый элемент |

### 2.2 EI-02 — JMX baseline: `FACT_ABSENCE`, подтверждено

Baseline-фикстуры JMX-контракта в репозитории нет. Проверено: glob `**/*baseline*` даёт 20 файлов, **ни одного** JMX-связанного (все — JMH/perf: `platform-tracing-bench/src/test/resources/jmh-baseline-fixtures/*`, `platform-tracing-bench/baselines/windows-i5-13500-pr0/baseline-metadata.json`, `platform-tracing-perf-harness/config/profiles/s0-baseline.yaml` и т. п.). Grep `jmx-management-contract|management-contract-baseline|contract-baseline` даёт 3 попадания, **все в документации и все в будущем времени**, включая `docs/analysis/plans/immutable_config_namespace_split_ba8da442.plan.md:185` («Store baseline in repo (e.g. …)») — формулировка to-do.

`DomainMBeanJmxComplianceTest` **существует** (`platform-tracing-otel-javaagent-extension/src/test/java/.../jmx/DomainMBeanJmxComplianceTest.java`, 133 строки, 9 `@Test`), но фикстур-файл не читает: проверяет регистрацию 6 доменных MBean (не `CONTROL_PROTOCOL`, не `EXTENSION_READINESS`) и по одному члену на домен — `SamplingRatio:46`, `updateScrubbingPolicy:58`, `updateValidationPolicy:69`, `ExportQueueSize:80`, `ProcessorErrorsTotal:91`, `InvalidConfigCount:102`, отсутствие старого `type=Control,name=PlatformTracingControl:113`, значение `0.77:122`.

`INFERRED`: тест покрывает **6 из 71** management-члена (см. §6, R-04) и не способен обнаружить добавление, удаление, переименование члена или изменение сигнатуры. Golden-file блокировки JMX-поверхности не существует. Следовательно требование отчётов 01 §2.3, 09, 10 (Gate E1) «baseline до любого удаления члена» остаётся не выполненным, и **удаление write-операций не авторизовано**.

Побочная находка (`FACT`): `platform-tracing-spring-boot-autoconfigure/.../jmx/PlatformTracingJmxObjectNames.java:13-26` дублирует **7 из 8** ObjectName (без `CONTROL_PROTOCOL`), а его Javadoc:9 ссылается на `ObjectNameConsistencyTest` как на гарантию согласованности. Glob по `ObjectNameConsistencyTest.java` даёт **0 файлов** — `FACT_ABSENCE`. Дублирование таблицы имён сегодня ничем не защищено от расхождения.

### 2.3 EI-03 — Числа ключей: и «64», и «67» неверны (разрешение C-03)

Ни одно число из прозы не принято. Инвентарь сгенерирован из кода.

| Источник объявлений | Точное число | Примечание |
|---|---|---|
| `TracingProperties` листовых полей | **36** | Не считая 14 `final` вложенных контейнеров (`sampling`, `scrubbing`, `exporter`, `response`, `suppression`, `validation`, `semantic`, `semantic.exception`, `propagation`, `propagation.platformHeaders`, `propagation.outbound`, `kafka`, `threadPoolTaskExecutor`, `actuator`) |
| `ExtensionPropertyNames` константы | **42** | Из них 2 — **не** config-свойства и 1 — импортированный ключ (ниже) |
| `PropagationDefaults` `PROP_*` | **12** | Полностью проигнорированы всеми подсчётами совета |
| `PlatformResourceProvider` `PROP_*` | **7** | Полностью проигнорированы всеми подсчётами совета |

Арифметика:

```text
Сырая сумма четырёх классов:                         36 + 42 + 12 + 7 = 97
Agent-плоскость, только platform.tracing.*:          (42 − 2) + 12 + 7 = 59   (внутренних дублей нет)
Spring-плоскость:                                                       36
Пересечение точных ключей Spring ∩ agent:                               17
Объединение platform.tracing.*:                      36 + 59 − 17     = 78
  − platform.tracing.policy.version (RESOURCE_ATTRIBUTE)                −1 = 77
  + platform.tracing.test.semconv-strict (@ConditionalOnProperty)       +1 = 78

>>> ТОЧНОЕ число различных CONFIG_PROPERTY имён platform.tracing.*  = 78
>>> + platform.environment (IMPORTED_PLATFORM_KEY, читается как config) = 79 platform-owned
>>> ТОЧНОЕ число различных ENV_VARIABLE имён, читаемых кодом            = 27
```

**Откуда взялись «64» и «67».** Наивная сумма `36 + 42 = 78` сворачивается в **69** различных имён после снятия 9 точных дублей и в **66** имён `platform.tracing.*` после исключения `platform.environment`, `otel.javaagent.extensions` и `platform.tracing.policy.version`. Любое число в полосе 64–67 — это фигура «A ∪ B», в которой отсутствуют `PropagationDefaults` (+4 непересекающихся) и `PlatformResourceProvider` (+7). Fable «67 = 36 + 31» использует урезанный набор `ExtensionPropertyNames`; досье «64» использует ещё более урезанный. **Оба — `MODEL_ERROR`.**

**17 двойных объявлений** (один и тот же точечный ключ объявлен в двух плоскостях). Spring ∩ `ExtensionPropertyNames` — 9:

| # | Ключ | Дефолт Spring / agent | Дрейф |
|---|---|---|---|
| 1 | `platform.tracing.mode` | `SdkMode AGENT` / String `null` через `nullableString` | **тип + дефолт** |
| 2 | `platform.tracing.sampling.enabled` | `true` / `true` | — |
| 3 | `platform.tracing.sampling.ratio` | `0.1` / `0.1d` | — |
| 4 | `platform.tracing.sampling.route-ratios` | `Map<String,Double>` / `Map<String,String>` | **тип** |
| 5 | `platform.tracing.sampling.drop-paths` | идентичный список из 3 | — |
| 6 | `platform.tracing.scrubbing.enabled` | `true` / `true` | — |
| 7 | `platform.tracing.scrubbing.built-in-rules` | 12 литералов hardcoded / вывод из `BuiltInSpanAttributeScrubbingRules.defaultConfigNames()` | **два источника истины, сейчас совпадают** |
| 8 | `platform.tracing.validation.enabled` | `true` / `true` | — |
| 9 | `platform.tracing.validation.strict` | `false` / `false` | — |

Spring ∩ `PropagationDefaults` — 8: три `propagation.platform-headers.*-header` и пять `propagation.outbound.*`. Значения совпадают, но **все пять agent-читателей outbound не вызываются ни в одном source set** (`FACT_ABSENCE` вызовов `isOutboundEnabled`, `getTrustedHostPatterns`, `isPropagate*`).

`PlatformResourceProvider` не пересекается ни с одним набором: его 7 имён `platform.tracing.service.*` существуют только в agent-плоскости, без Spring-биндинга.

**Имена в `ExtensionPropertyNames`, которые НЕ являются config-свойствами (2 из 42):**

1. `RESOURCE_POLICY_VERSION_ATTR = "platform.tracing.policy.version"` → `RESOURCE_ATTRIBUTE`. Используется только как `AttributeKey.stringKey(...)` в `ResourceAttributeResolver:45` и `ResourceStartupDiagnostics:27`; никогда не передаётся в `ConfigProperties`. Его config-аналог — `RESOURCE_POLICY_VERSION = "platform.tracing.resource.policy-version"`. **Критично:** целевой namespace плана — `platform.tracing.policy.*`. Это имя уже занято resource-атрибутом. Коллизия обязана быть разрешена до миграции.
2. `OTEL_JAVAAGENT_EXTENSIONS = "otel.javaagent.extensions"` → `STANDARD_OTEL_KEY`, владелец — OpenTelemetry, не платформа.

Плюс `PLAT­FORM_ENVIRONMENT = "platform.environment"` читается как config, но вне namespace `platform.tracing.*` → `IMPORTED_PLATFORM_KEY`.

**Мёртвые и рассогласованные имена (`FACT`), которые обязаны попасть в disposition-таблицу:**

| Имя | Локация | Состояние |
|---|---|---|
| `platform.tracing.enabled` | 14 YAML в `platform-tracing-perf-harness`; `gradle/c3-published-consumer/.../ConsumerApplication.java:16` | **DEAD.** Нет листа, нет `@ConditionalOnProperty`, нет `Environment.getProperty`. Удалён по `MIGRATION.md:24`, `CHANGELOG.md:11`. Молча игнорируется relaxed binding |
| `platform.tracing.sdk.mode` | `gradle/c3-published-consumer/.../ConsumerApplication.java:17` | **DEAD.** Константа называется `SDK_MODE`, но её значение — `platform.tracing.mode`. Документы `extension-configuration-refactoring-dossier.md:153,235` и `extension-configuration-package-inventory.md:102,322,725,775` утверждают обратное — устаревшая проза, опровергнутая кодом |
| `platform.tracing.semconv.validation.mode` | `docs/contracts/public-contract-inventory.yaml:704`, `semantic-contract-map.yaml:509` | **Опубликованный инвентарь контрактов указывает на несуществующий ключ.** Реальный — `platform.tracing.semantic.validation-mode` |
| `platform.tracing.propagation.w3c.diagnostics-only` | `PropagationDefaults` | Константа **без читателя и без документации** — единственное такое имя в четырёх классах констант |
| `platform.tracing.policy.sampling.ratio`, `platform.tracing.policy.sampling.route-ratio` | `configProvenance` тесты | `FIXTURE_ONLY` — ключи характеризационного теста, production-биндинга нет |

**Побочно:** `platform.tracing.actuator.mutation-enabled` появляется в `config/k8s/perf-app-configmap.yaml` как env `PLATFORM_TRACING_ACTUATOR_MUTATION_ENABLED` — `RELAXED_BINDING_ALIAS` без Java-константы.

**Вердикт C-03:** число ключей — `SUPERSEDED_BY_REPOSITORY`. Финальная миграция namespace **не одобряется**: помимо неверного счёта, не разрешены как минимум коллизия `platform.tracing.policy.version`, два независимых семейства baggage-ключей (`baggage.*` читается `BaggageExtensionConfig`, `propagation.baggage.*` — `BaggagePropagationCustomizer`), расхождение написаний `sampling.force-record-header-values` (Spring) против `sampling.force-record-values` (agent) и опубликованный контракт с битым ключом.

### 2.4 EI-04 — «Fable: E3 закрыт» — `MODEL_ERROR`

Fable §D-05: «Gate E3 закрыт: `RETAIN_GENERIC` подтверждён кодом». Это утверждение опровергается тем же кодом — см. §5, C-01. Отчёт 04 §15 перечисляет шесть условий реверсии; условие 3 («update semantics cannot distinguish stale conflict from internal failure») **выполнено фактически**. Gate E3 остаётся **OPEN**.

### 2.5 EI-05 — `READY_FOR_IMPLEMENTATION_GO_REVIEW` (Gemini) не является обязывающим

Постановка задачи прямо запрещает трактовать этот токен как обязывающий, и репозиторные факты подтверждают запрет: Gemini трактует Step 0B как подтверждённый (опровергнуто §2.1), предлагает `newMBeanServer` при одновременном требовании discovery со стороны Spring (опровергнуто §7 — внутренне противоречиво), и в D-11 указывает «Deletion consequence: N/A» для стратегии зависимостей, пропуская дефект EOL-BOM (§8). Токен переклассифицирован в **не обязывающий**.

---

## 3. Единогласные решения

Все три члена совета и все относящиеся исправленные анализы согласны; репозиторные факты не опровергают. Обязывающий статус — `ACCEPT`, если не указано иное.

| # | Решение | Репозиторное подтверждение | Обязывающий статус |
|---|---|---|---|
| U-01 | Agent-first production topology | `FACT` — `docs/SUPPORTED.md:20,42`: Spring Boot 3.5.x (validated 3.5.5) + Controlled Platform Agent; SDK-only production не поддерживается | `ACCEPT` |
| U-02 | Никакой production-паритета Local SDK | `FACT` — `platform.tracing.sdk.mode` мёртв, живёт только в fixture `gradle/c3-published-consumer` | `ACCEPT` |
| U-03 | Ни одного нового Gradle-модуля | `FACT` — `settings.gradle`, 16 `include`; жёсткое ограничение постановки | `ACCEPT` |
| U-04 | Один Agent-side владелец принятой runtime-политики | `PLAN`; сегодня `FACT` — 6 независимых mutable-владельцев | `ACCEPT` (направление) |
| U-05 | Одна агрегатная граница записи | `PLAN`; сегодня `FACT` — 16 мутирующих JMX-операций + 1 HTTP + ungated RefreshScope | `ACCEPT` (направление) |
| U-06 | Приватный process-local writer | Механизм **разрешён** в §7 исполняемым спайком | `ACCEPT` механизма; `SPIKE_REQUIRED` для внутрирепозиторной реализации (S-03) |
| U-07 | Платформенный MBeanServer read-only после cutover | `PLAN`; точная дельта членов блокирована отсутствием baseline (§2.2) | `ACCEPT` цели; удаление блокировано E1 |
| U-08 | Config Server как production-авторитет желаемого состояния | `FACT_ABSENCE` в production: ни bridge, ни guard, ни Config Client | `ACCEPT` условно + `GOVERNANCE_REQUIRED` |
| U-09 | DEV_ACTUATOR — non-production, только JMX | `PLAN`; `@JmxEndpoint` нигде не используется сегодня | `ACCEPT` |
| U-10 | Никакой HTTP-мутации в целевом состоянии | `FACT` — сегодня ровно 1 `@WriteOperation` во всём репозитории, `TracingActuatorEndpoint.java:202` | `ACCEPT` |
| U-11 | Никаких прямых доменных JMX-сеттеров после cutover | `FACT` — сегодня 16 мутирующих операций (§6 R-04) | `ACCEPT` цели; удаление блокировано E1 |
| U-12 | `scrubbing.rules-config` — startup-only (Category B) | `FACT` — `ScrubbingRulesLoader`: строка-путь, схемы `classpath:`/`file:`/bare, только Java Properties, YAML отвергается, загрузка при построении pipeline | `ACCEPT` |
| U-13 | Никаких сырых секретов в snapshot/digest/audit/projection/logs | `FACT` — `platform.tracing.scrubbing.hmac-key` существует как agent-ключ | `ACCEPT` + `SECURITY_APPROVAL_REQUIRED` на инвентарь секретов |
| U-14 | Полный snapshot, а не patch-apply | `PLAN`; поддержано T9/T10 (но см. §2.1 — `UNVERIFIED`) | `ACCEPT` |
| U-15 | Никаких compatibility-алиасов | Pre-production политика репозитория; `.cursor/rules/00-global.mdc` | `ACCEPT` |
| U-16 | Никакой released dual-архитектуры | — | `ACCEPT` |
| U-17 | Один связный неопубликованный cutover-train | — | `ACCEPT` |
| U-18 | Текущее принятое состояние — логический LKG | `FACT` — при неудачном CAS предыдущее принятое состояние сохраняется | `ACCEPT` |
| U-19 | Никакого отдельного полного LKG-payload | Отчёт 09 §2: «Do not store another policy copy»; условие реверсии не выполнено | `ACCEPT` |

Ни один пункт обязательного durable-консенсуса не отклонён репозиторными фактами. U-06 усилен: механизм больше не гипотеза.

---

## 4. Решения сильного консенсуса

| # | Решение | Расхождение | Обязывающий статус |
|---|---|---|---|
| S-01 | Двумерная модель: `mode=AGENT\|DISABLED` ⊥ `authority=HELM\|CONFIG_SERVER\|DEV_ACTUATOR`, авторитет управляет **только** `policy.*` | Gemini формулирует авторитет как строгую иерархию `CONFIG_SERVER > DEV_ACTUATOR > HELM`, что смешивает приоритет с ортогональностью; отчёт 06 §1.3 и Fable/GPT — ортогональность | `ACCEPT` формулировку 06/Fable/GPT; Gemini D9 — `MODEL_ERROR` в части «иерархии» |
| S-02 | `DISABLED+CONFIG_SERVER` и `DISABLED+DEV_ACTUATOR` → fail-fast | Отчёт 06 «Reject by default»; GPT/Fable — явный fail-fast | `ACCEPT` |
| S-03 | `authority`, `mode`, `dev-control.enabled`, доверенность `platform.environment` **не могут** приходить из Config Server | Единогласно | `ACCEPT` |
| S-04 | `EnvironmentChangeEvent` — триггер; `changedKeys` не применяются как patch; каждый цикл строит полный manifest | Единогласно | `ACCEPT`, но фактическая основа `UNVERIFIED` до S-02 |
| S-05 | Коалесцирование generation/dirty одним worker'ом; event-dropping `AtomicBoolean` отвергнут | Отчёт 05 §713-714, Fable, GPT | `ACCEPT` |
| S-06 | Никакого `@RefreshScope` в целевом bridge; `TracingRefreshScopeAutoConfiguration` удаляется | Единогласно | `ACCEPT` цели |
| S-07 | `PlatformHeaders` — единственный контракт имён заголовков; конфигурируемые имена заголовков удаляются | Отчёт 06 §7, GPT Table 2 №12/22/23/24, Fable DELETE-список | `ACCEPT` |
| S-08 | `CONFIG_SNAPSHOT_V1` добавляется в `PlatformExtensionCapability`; означает поддержку, не успех первого apply; Spring только читает | Единогласно | `ACCEPT`. `FACT` — сегодня enum содержит **7** констант, `CONFIG_SNAPSHOT_V1` отсутствует (0 попаданий grep по репозиторию) |
| S-09 | Audit — in-memory ring фиксированной ёмкости **32** записи на процесс, только метаданные, без payload/секретов/стектрейсов | Единогласно (отчёт 09, все три члена) | `ACCEPT` |
| S-10 | Structural drift: initial mismatch блокирует readiness приложения; runtime drift → READY + устойчивый `restartRequired=true`; никакого нового `DEGRADED` | Единогласно | `ACCEPT` |
| S-11 | `sourceRevision` опционален и **никогда** не является входом упорядочивания/конкурентности; читается напрямую из marker-source `configClient` | Отчёты 01 §2.5, GPT §4.1, Fable | `ACCEPT`; фактическая основа T7/T8 `UNVERIFIED` до S-02 |
| S-12 | Boot 3.5.6 **не** является обязательным сам по себе | Fable D-11, GPT (косвенно), отчёт 05 §131-133; Gemini не рассматривает | `ACCEPT` |
| S-13 | Starters **не** тянут Config Client транзитивно; сервис в режиме CONFIG_SERVER добавляет `spring-cloud-starter-config` сам | Единогласно | `ACCEPT` |
| S-14 | Диагностика: 4 неперекрывающихся владельца; per-domain `*ConfigVersion` и `*LastUpdatedSource` удаляются **после** baseline; runtime-метрики поведения сохраняются | Отчёт 09 §3, все три члена | `ACCEPT` направления; точная дельта блокирована E1 |
| S-15 | Открытые типы JMX (Open Types) — корректная cross-classloader граница; публичных control-DTO не создаётся | Единогласно; отчёт 01 §2.1 | `ACCEPT` |

---

## 5. Существенные разногласия

### 5.1 C-01 — `VersionedStateHolder`: разрешено кодом, но выигравшего варианта нет

Решение **не** принималось голосованием. Инспектированы: `VersionedStateHolder`, `VersionedState`, `VersionedStateHolderTest`, `SamplerStateHolder`, `ScrubbingPolicyHolder`, `ValidationPolicyHolder` и типы снапшотов.

**Позиции:**

| Член | Позиция |
|---|---|
| Fable 5 | `RETAIN_GENERIC` + добавить `public boolean compareAndSet(T expected, T next)` («~6 строк, ноль tracing-концепций»). Заявляет «Gate E3 закрыт» |
| GPT-5.6 SOL | `REFACTOR_DOMAIN_SPECIFIC`: `AcceptedTracingPolicyStateStore` с одним `AtomicReference` и `compareAndCommit(expectedInstanceId, expectedSequence, candidate)`; удалить `VersionedStateHolder` после снятия текущих holder'ов |
| Gemini 3.1 Pro | `RETAIN_GENERIC`; сопоставления на уровне API не приводит |
| Исправленный анализ 04 | `RETAIN_GENERIC`, **«subject to an exact API review»**, с шестью условиями реверсии (§15) |
| Исправленный анализ 11 | F1 = 531/640 (83.0), F2 = 504/640 (78.8); победитель F1 **при условии Gate E3** |

**Ответы на восемь обязательных вопросов:**

**Q1. Какое именно поведение даёт текущий retry-loop?**
`FACT` — `tryUpdate` реализован как **неограниченный `for(;;)` retry-loop, а не одиночный CAS**. Проигранная гонка **никогда не доходит до вызывающего** — она поглощается циклом. Builder (`UnaryOperator`) и валидатор **переисполняются на каждой итерации**. Под контентом это заново запускает компиляцию регулярных выражений (`ScrubbingSnapshot.compileWrappers`) и полную нормализацию политики (конструктор `SamplerState`).

**Q2. Выразима ли expected-base one-shot семантика безопасно, без модификации generic API?**
`FACT` — **да, выразима**: `tryUpdate(prev -> prev.version() == expected ? next : null)`. Ветвь null-кандидата завершает цикл, а любой конкурентный успех инвалидирует предикат, поэтому livelock доказуемо невозможен. **Это опровергает посылку GPT-5.6** о том, что семантика невыразима.

Но выразимость ≠ достаточность. Три ограничения (`FACT`): результат — bare `boolean`; невозможно атомарно с отказом прочитать фактическую текущую версию; и требуется семантический каламбур «null означает сохранить last-known-good».

**Q3. Сохранит ли добавление `compareAndSet(T expected, T next)` осмысленную generic-абстракцию?**
`INFERRED` — частично. Метод сам по себе generic-нейтрален. Но он **не решает** главную проблему Q4/Q6: bare `boolean` по-прежнему не различает исходы. Предложение Fable «делает single-CAS контракт буквальным», не делая его **классифицируемым**.

**Q4. Различимы ли `STALE`, `REJECTED`, `FAILED`?**
`FACT` — **нет.** `REJECTED` (отказ валидации), `FAILED` (исключение), null-кандидат и поглощённый `InterruptedException` — все схлопываются в один и тот же `false`. `STALE` вообще не представим, потому что проигрыш гонки поглощается циклом. Двухаргументная перегрузка с валидатором имеет **ноль production-вызовов**: все три доменных holder'а выполняют доменную валидацию в отдельном pre-CAS канале, который проглатывает `IllegalArgumentException` в `false`.

**Это выполняет условие реверсии №3 из отчёта 04 §15.** Заявление Fable «E3 закрыт» — `MODEL_ERROR`.

**Q5. Остаётся ли у generic-типа реальный названный потребитель после удаления трёх holder'ов?**
`FACT` — **нет.** Единственные production-инстанцирования — `SamplerStateHolder`, `ScrubbingPolicyHolder`, `ValidationPolicyHolder`. После их удаления у primitive не остаётся потребителей, кроме собственного теста. Это частично выполняет условия реверсии №4 и №6.

**Q6. Кто владеет версией?**
`FACT` — holder **никогда не вычисляет и не инкрементирует** `version()`; он делегирует снапшоту **без проверки монотонности** в `tryUpdate`. `prev.version() + 1` выписано вручную в **8 местах**; `normalizeSource` независимо реализован **3 раза**; а `SamplerStateHolder.update(SamplerState)` публикует снапшот, переданный вызывающим, **без инкремента вообще** — то есть версия может повториться или регрессировать. Это выполняет условие реверсии №4 («duplicate version fields or competing sources of truth») в его фактической, а не гипотетической форме.

**Q7. Какой вариант даёт меньше долгоживущих ответственностей?**
`INFERRED` — F2 (domain-specific). F1 сохраняет generic-модуль `platform-tracing-otel` в роли владельца CAS-примитива с нулём потребителей вне Agent-extension, что противоречит направлению зависимостей. F2 размещает единственного владельца там же, где единственный потребитель.

**Q8. Какой даёт самую ясную классификацию исходов и легче фальсифицируется?**
`INFERRED` — F2. Классификация `APPLIED|UNCHANGED|REJECTED|STALE|FAILED` требует, чтобы операция коммита возвращала **различающий** результат, а не boolean. Тестируемость: `VersionedStateHolderTest` сегодня **не содержит** ни ABA-сценария, ни сценария отказа валидации под контентом; его единственный concurrency-тест доказывает только отсутствие потерянных обновлений.

**Вердикт C-01.** Репозиторные доказательства **достаточны, чтобы объявить Gate E3 не пройденным**, и **недостаточны, чтобы объявить победителя**, потому что F1 остаётся спасаемым либо `compareAndSet`, либо расширением возвращаемого типа, а обе правки затрагивают публичный generic API, который постановка требует держать свободным от tracing-концепций. Победитель **не назначается**. Определён двухвариантный спайк **S-01** с обязывающим критерием выигрыша (см. 13b).

Обязывающий статус: **`SPIKE_REQUIRED`**. Существующая рекомендация `RETAIN_GENERIC` (отчёты 04, 11, 12; Fable; Gemini) переклассифицируется из «binding» в **«условная, гейт не пройден»**.

### 5.2 C-04 — Ширина Category A: решено по свойствам, не по большинству

Предложения: Gemini — 5 ключей; GPT-5.6 — 10; Fable — 11 (включая scrubbing built-in rules).

Объединение всех предложений даёт 12 листьев. Каждый оценён по девяти обязательным измерениям. Правило постановки применено буквально: сырые секреты, файловые пути, композиция pipeline и контракты имён заголовков **не входят** в Category A без экстраординарных доказательств.

| Лист (целевое имя) | Текущий producer | Текущий runtime consumer | Текущий путь мутации | Incident/SRE-потребитель | Риск безопасности | Доказательство runtime-безопасности | Риск производительности | Целевая валидация | Метрика/audit | Итог |
|---|---|---|---|---|---|---|---|---|---|---|
| `policy.sampling.enabled` | `TracingProperties:3` + `ExtensionPropertyNames.SAMPLING_ENABLED` | `CompositeSampler` через `SamplerStateHolder` | `setSamplerEnabled` (JMX), `updateSamplingPolicy`×2, Actuator `samplerEnabled`, RefreshScope | Да — kill-switch объёма | Низкий | Мутируется сегодня, CAS-защищён | Одно чтение `AtomicReference` | boolean | outcome+authority | **INCLUDE** |
| `policy.sampling.ratio` | `TracingProperties:4` + `SAMPLING_RATIO` | `CompositeSampler` | `setSamplingRatio`, `updateSamplingPolicy`×2, Actuator `samplingRatio`, RefreshScope, `PerfAdminController` | Да — основной инцидентный рычаг | Низкий | Мутируется сегодня | Нет | `[0.0, 1.0]` замкнутый | outcome+authority | **INCLUDE** |
| `policy.sampling.route-ratios` | `TracingProperties:5` + `SAMPLING_ROUTE_RATIOS` | `CompositeSampler` | `setRouteRatios`, `updateSamplingPolicy`×2 | Да — таргетированная диагностика | Низкий | Мутируется сегодня | Ограниченная map; **дрейф типа** `Map<String,Double>` против `Map<String,String>` обязан быть устранён | размер map + диапазон значений + границы длины ключа | outcome | **INCLUDE** при условии канонизации типа |
| `policy.sampling.drop-paths` | `TracingProperties:7` + `SAMPLING_DROP_PATHS` | `CompositeSampler` | `setDropPathPrefixes`, `updateSamplingPolicy`×2 | Оспаривается | Средний — управляет тем, какой трафик наблюдаем; ошибочный runtime-drop скрывает инциденты | Мутируется сегодня | Ограниченный список | размер списка + длина элемента + отсутствие regex | outcome | **NEEDS_SRE_DECISION** |
| `policy.sampling.force-record-values` | `TracingProperties:6` (`force-record-header-values`) + `SAMPLING_FORCE_RECORD_VALUES` (**иное написание**) | `CompositeSampler` | `setForceRecordValues`, `updateSamplingPolicy`×2 | Оспаривается | **Средне-высокий** — это значения, сопоставляемые с `X-Trace-On`; runtime-расширение меняет то, кто может форсировать запись → вектор усиления/DoS | Мутируется сегодня | Ограниченный список | размер+длина, запрет wildcard | outcome + счётчик forced-decision | **NEEDS_SECURITY_DECISION** |
| `policy.validation.enabled` | `TracingProperties:14` + `VALIDATION_ENABLED` | `ValidatingSpanProcessor` через `ValidationPolicyHolder` | `updateValidationPolicy`×2, `applyPolicy` | Да | Низкий | Мутируется сегодня | Нет | boolean | outcome | **INCLUDE** |
| `policy.validation.strict` | `TracingProperties:15` + `VALIDATION_STRICT` | `ValidatingSpanProcessor` | `updateValidationPolicy`×2, `applyPolicy` | Да | Низкий при сохранении startup-огибающей | `javaagent.validation.strict-runtime-allowed` — startup-only предохранитель (`FACT`, `ValidationExtensionConfig`) | Нет | boolean **∧** startup-огибающая | outcome | **INCLUDE** при условии, что огибающая остаётся Category B |
| `policy.exporter.enabled` | `TracingProperties:10` | `SafeSpanExporter` | `setExportEnabled`, Actuator `exportEnabled`, RefreshScope, `PerfAdminController` | Да — kill-switch экспорта | Низкий | Мутируется сегодня | Нет | boolean | outcome | **INCLUDE** |
| `policy.propagation.trace-control-headers.enabled` | `TracingProperties:21` (`propagation.enabled`) | gate распространения | `setPropagationEnabled`, Actuator, RefreshScope | Да | Низкий — это **gate**, не имя заголовка | Мутируется сегодня | Нет | boolean | outcome | **INCLUDE** при точном переименовании (не «propagation.enabled», не W3C-gate) |
| `policy.diagnostics.log-level` | `TracingProperties:2` (`log-level`) | `PlatformLogControl` | `setPlatformLogLevel`, Actuator `logLevel`, RefreshScope | Да | Низкий **при условии** secret-safe на всех уровнях | Мутируется сегодня | Нет | замкнутый enum `OtelAgentLogLevel` | outcome | **INCLUDE** при точном имени (не «policy.log-level») |
| `policy.scrubbing.built-in-rules` | `TracingProperties:9` + `SCRUBBING_BUILT_IN_RULES` | `ScrubbingSpanProcessor` через `ScrubbingPolicyHolder` | `updateScrubbingPolicy`×2 | Не назван | **Высокий** — это композиция pipeline контролей приватности; runtime-сужение набора правил молча отключает скрабинг PII без изменения `scrubbing.enabled` | Мутируется сегодня, **но** `applyPolicy` возвращает `SUCCESS`, ничего не применяя (§6 R-04) | `ScrubbingSnapshot.compileWrappers` перекомпилирует regex, а retry-loop переисполняет builder → выделения под контентом | — | — | **EXCLUDE** |
| `scrubbing.enabled` | `TracingProperties:8` + `SCRUBBING_ENABLED` | `ScrubbingSpanProcessor` | `updateScrubbingPolicy`×2 | Не назван | **Высокий** — runtime-отключение санитайзера | Отвергнуто всеми тремя членами | — | — | — | **EXCLUDE** |

**Консолидированный результат:** 8 `INCLUDE` (три из них с условием точного имени/типа/огибающей), 2 требуют решения (`drop-paths` → SRE, `force-record-values` → security), 2 `EXCLUDE`.

Это **не** список ни одного из членов совета. Он ближе к 10 ключам GPT-5.6, но отличается двумя вынесенными на решение листьями. Ключевое расхождение с Fable: `scrubbing.built-in-rules` **исключается** — предложение Fable нарушает прямой запрет постановки на композицию pipeline в Category A и не сопровождается экстраординарными доказательствами. Ключевое расхождение с Gemini: пять ключей исключают `validation.*` и propagation-gate, которые мутируемы сегодня, имеют названных SRE-потребителей и низкий риск; отбрасывание их без доказательства — сужение без обоснования.

Обязывающий статус: **`SRE_APPROVAL_REQUIRED`** (drop-paths) и **`SECURITY_APPROVAL_REQUIRED`** (force-record-values). Полная заморозка Category A остаётся **UNRESOLVED**. Пакет решения — S-05.

### 5.3 C-05 — Production-стек Spring Cloud: обнаружен независимый дефект

**Ни одно** из двух предположений постановки не принято. Проверено: текущая версия Boot, текущие зависимости Spring Cloud, констрейнты BOM, стек-кандидат Step 0B, официальная матрица совместимости.

Репозиторные факты:

| Факт | Доказательство |
|---|---|
| Boot `3.5.5` | `gradle.properties:10` `springBootVersion=3.5.5` |
| `spring-cloud-context` `4.1.5` | `gradle.properties:13` `springCloudContextVersion=4.1.5` |
| **Публикуемый BOM транслирует его как `api`** | `platform-tracing-bom/build.gradle:29` — `api "org.springframework.cloud:spring-cloud-context:${springCloudContextVersion}"` |
| Autoconfigure использует его как `compileOnly` | `platform-tracing-spring-boot-autoconfigure/build.gradle:50` — `compileOnly 'org.springframework.cloud:spring-cloud-context'` |
| Config Client в production отсутствует | Grep `spring-cloud` даёт только BOM, autoconfigure `compileOnly` и **некоммитнутый** `configProvenance` |
| Документированная поддержка | `docs/SUPPORTED.md:49` — «Spring Cloud Context \| 4.1.x (optional) \| 4.1.5 \| `@RefreshScope` only; not required» |
| Стек-кандидат Step 0B разрешается | `FACT` — Boot 3.5.6 / Cloud BOM 2025.0.0 / config-client, context, config-server 4.3.0 / JGit 6.10.1 |

Официальная матрица (внешнее доказательство, уровень 6, использовано только для внешнего факта):

```text
2025.0.x (Northfields)  -> Spring Boot 3.5.x   ; spring-cloud-commons/config 4.3.x
2024.0.x (Moorgate)     -> Spring Boot 3.4.x   ; 4.2.x
2023.0.x (Leyton)       -> Spring Boot 3.2.x, 3.3.x ; 4.1.x     [помечен EOL]
```

`spring-cloud-context` является артефактом `spring-cloud-commons`. Отсюда:

**`FACT` + `INFERRED`: репозиторий публикует межпоездный констрейнт на артефакт из EOL-поезда, в области `api`, приблизительно 80 сервисам-потребителям.** `spring-cloud-context:4.1.5` принадлежит поезду 2023.0.x, целящемуся в Boot 3.2/3.3 и находящемуся в статусе end-of-life, тогда как сам репозиторий закрепляет Boot 3.5.5. `docs/SUPPORTED.md:49` документирует эту комбинацию как поддерживаемую.

Это **не** вопрос про Config Server. Дефект существует уже сейчас, в опубликованном BOM, вне зависимости от того, будет ли когда-либо принят `authority=CONFIG_SERVER`. Ни Fable, ни GPT-5.6, ни Gemini его не идентифицировали: GPT предлагает «убрать standalone 4.1.5 и импортировать Cloud BOM» по причинам выравнивания, не отмечая ни область `api`, ни EOL; Fable корректно снимает обязательность Boot 3.5.6, но не касается BOM-констрейнта; Gemini в D-11 пишет «Deletion consequence: N/A».

**Вердикт C-05:** оба предположения постановки отклонены. «Boot 3.5.6 обязателен» — не доказано (S-12 подтверждён). «Boot 3.5.5 + `spring-cloud-context` 4.1.5 — валидный production Config Client stack» — **опровергнуто**: это не только не Config Client stack (Config Client отсутствует), это межпоездная EOL-комбинация, транслируемая потребителям.

Обязывающий статус: **`GOVERNANCE_REQUIRED`**. Точные эксперименты с зависимостями и consumer-smoke-тесты — S-06.

### 5.4 C-06 — Обнаружение приватного `MBeanServer`: разрешено исполняемым спайком

**Позиции:**

| Член | Позиция |
|---|---|
| Gemini | «Use `newMBeanServer` over `createMBeanServer`, no remote connector» (Table 7); одновременно Phase 1 требует «Prove Spring `TracingConfigServerBridge` can locate it» |
| GPT-5.6 SOL | `createMBeanServer(...)`, discovery через `findMBeanServer`, затем `unregister` + `releaseMBeanServer` на shutdown; «`createMBeanServer` is factory-retained/discoverable whereas `newMBeanServer` is not» |
| Fable 5 | `MBeanServerFactory.createMBeanServer("space.br1440.platform.tracing.internal")`, «retained-вариант — намеренно», discovery через `findMBeanServer` + ровно один marker-MBean |
| Отчёт 08 | Называет только `MBeanServerFactory`; четыре конкретных метода не перечисляет. Отчёт 12 A-11 добавляет различие create/new |

Спайк выполнен на установленной `java 21.0.6+8-LTS-188`, вне репозитория, два процесса. Результаты — `FACT`:

| # | Наблюдение | Значение |
|---|---|---|
| 1 | `findMBeanServer(null)` до любых вызовов | `0` |
| 2 | После `ManagementFactory.getPlatformMBeanServer()` | `1`, и список **содержит платформенный сервер** (`platform IS retained by factory = true`) |
| 3 | `createMBeanServer(domain)` | обнаруживается: `findMBeanServer(null)` → `2`, `contains(created) = true` |
| 4 | `newMBeanServer(domain)` | **не** обнаруживается: размер остаётся `2`, `contains(news) = false` |
| 5 | `findMBeanServer("space.br1440.platform.tracing.internal")` | **`0`** |
| 6 | `findMBeanServer("<MBeanServerId>")`, где Id = `DESKTOP-…_1785148517461` | `1` |
| 7 | Один и тот же ObjectName зарегистрирован **одновременно** на приватном и платформенном сервере | `true` — уникальность ObjectName скоупится сервером |
| 8 | Повторный `createMBeanServer(тот же домен)` | **разрешён** — два сервера с одинаковым default domain |
| 9 | После `releaseMBeanServer(created)` | список `1`, `contains = false`, **но `created.isRegistered(marker) = true`** — сервер полностью работоспособен |
| 10 | `releaseMBeanServer(newMBeanServer(...))` | `IllegalArgumentException: MBeanServer was not in list!` |
| 11 | Через удалённый коннектор (`com.sun.management.jmxremote.port=17099`): `queryNames("space.br1440.platform.tracing:*")` | **только** платформенная проекция `type=Readiness,name=PlatformExtension` |
| 12 | Удалённо: `isRegistered(writer на приватном сервере)` | `false`; `invoke` → `InstanceNotFoundException` |
| 13 | Удалённо: `invoke` write-операции MBean, зарегистрированного на **платформенном** сервере | **`SUCCEEDED`** |

**Разрешение.** Целевое состояние обязано использовать **factory-retained приватный сервер (`createMBeanServer`) плюс marker-MBean с перечислением списка фабрики**. Из шести обязательных свойств спайк доказывает четыре и переклассифицирует два:

| Требуемое свойство | Статус после спайка |
|---|---|
| Spring может локализовать ровно одного writer'а | `FACT` частично — `findMBeanServer(null)` возвращает **и** платформенный сервер (наблюдение 2), а `findMBeanServer(String)` сопоставляет `MBeanServerId`, **а не default domain** (наблюдения 5–6). Поскольку `MBeanServerId` — это host+timestamp, он непредсказуем. Следовательно discovery **обязан** быть «перечислить `findMBeanServer(null)` → отфильтровать по `isRegistered(markerObjectName)`». Наблюдение 7 подтверждает, что marker виден только на приватном сервере |
| Платформенный удалённый JMX не может его раскрыть | **`FACT` — доказано** (наблюдения 11–12) |
| Работает Agent-only топология | `INFERRED` — приватный сервер существует независимо от Spring; требует внутрирепозиторного подтверждения (S-03) |
| Несколько контекстов не дублируют его | **`FACT` — JDK этого НЕ обеспечивает** (наблюдение 8). Уникальность обязана обеспечиваться прикладным кодом. Ни один член совета этого не отметил |
| Shutdown очищает | **`FACT` — `releaseMBeanServer` НЕ является shutdown** (наблюдение 9): он снимает только discovery-ссылку, сервер и его MBean остаются рабочими. Обязателен явный `unregisterMBean` до release. Ни один член совета этого не отметил |
| `mode=DISABLED` не находит и не мутирует | `PLAN` — прикладной guard; S-03 |

**Вердикт C-06.** Рекомендация Gemini `newMBeanServer` — **`MODEL_ERROR` и внутренне противоречива**: наблюдение 4 показывает, что такой сервер невозможно найти через фабрику, тогда как её же Phase 1 требует, чтобы Spring его нашёл. Она не является просто «более безопасным вариантом»: она делает заявленный механизм discovery неработающим и потребовала бы другого локатора (статический аксессор или system property), который сам создал бы cross-classloader-связывание, которого архитектура Agent призвана избегать.

GPT-5.6 и Fable выбрали правильный метод. Все три члена совета упустили: (a) платформенный сервер находится в том же списке фабрики; (b) `findMBeanServer(agentId)` — не поиск по домену; (c) дубликаты разрешены; (d) release ≠ shutdown.

Дополнительно спайк превращает раздел 11.1 отчёта 07 из гипотезы в `FACT`: **write-операция, зарегистрированная на платформенном MBeanServer, удалённо вызываема и выполняется успешно** (наблюдение 13). Сегодня `applyPolicy(CompositeData)` зарегистрирован именно на платформенном сервере (`PlatformTracingJmxRegistrar` использует `ManagementFactory.getPlatformMBeanServer()`, строки 80-82). Обход существует в текущем коде.

Обязывающий статус: механизм — **`ACCEPT`**; внутрирепозиторная реализация — **`SPIKE_REQUIRED`** (S-03) + **`SECURITY_APPROVAL_REQUIRED`**.

---

## 6. Репозиторные факты, обнаруженные советом и проверенные арбитражем

Каждая строка перепроверена по коду. Отмечено, где утверждение совета оказалось неточным.

### R-01 — Топология и версии

| Факт | Доказательство | Метка |
|---|---|---|
| 16 активных Gradle-модулей | `settings.gradle`, 16 `include` | `FACT` |
| Boot 3.5.5, OTel BOM 1.62.0, Agent 2.28.1/2.28.1-alpha, версия 0.1.0-SNAPSHOT | `gradle.properties:10-13` | `FACT` |
| Agent-first; SDK-only production не поддерживается | `docs/SUPPORTED.md:20,42` | `FACT` |
| Java 21 toolchain | `build.gradle:25` | `FACT` |

### R-02 — Примитив состояния

Все пункты §5.1 (Q1–Q8). Наиболее существенные, ни одним членом совета не приведённые: `prev.version() + 1` выписано в **8 местах**; `normalizeSource` реализован **3 раза** независимо; `SamplerStateHolder.update(SamplerState)` публикует снапшот вызывающего **без инкремента**, допуская повтор или регресс версии; двухаргументная перегрузка валидатора имеет **0** production-вызовов. Метка: `FACT`.

### R-03 — Инвентарь конфигурации

Все пункты §2.3. Точное число `platform.tracing.*` CONFIG_PROPERTY = **78**; ENV_VARIABLE = **27**; 17 двойных объявлений; коллизия `platform.tracing.policy.version` с целевым namespace; два независимых семейства baggage-ключей; расхождение написаний force-record; опубликованный контракт `public-contract-inventory.yaml:704` указывает на несуществующий ключ. Метка: `FACT`.

### R-04 — JMX-контракт и пути мутации

| Факт | Доказательство | Метка |
|---|---|---|
| **8** ObjectName, объявленных дважды (String + `ObjectName`) = 16 публичных констант | `platform-tracing-otel-javaagent-extension/.../jmx/PlatformTracingObjectNames.java:17-41` | `FACT` |
| **71** management-член всего: **16 мутирующих**, **55** read-only | Разбор восьми MBean-интерфейсов; декомпозиция: **8** writable-атрибутов (`SamplerEnabled`, `RouteRatios`, `SamplingRatio`, `DropPathPrefixes`, `ForceRecordValues`, `ExportEnabled`, `PropagationEnabled`, `PlatformLogLevel`) + **8** мутирующих операций (`updateSamplingPolicy`×2, `resetSamplerCounters`, `updateScrubbingPolicy`×2, `updateValidationPolicy`×2, `applyPolicy`) | `FACT` |
| **Только 1 из 16** мутирующих операций проходит через `RuntimeControlMutationPolicy` | Enforcement — `RuntimePolicyControlHandler:89-92`, достижимо лишь через `applyPolicy`; остальные 15 — ungated прямые точки входа | `FACT` |
| Регистратор использует платформенный сервер | `PlatformTracingJmxRegistrar:80-82` — `this(ManagementFactory.getPlatformMBeanServer(), new PlatformExtensionReadiness())` | `FACT` |
| `MBeanServerFactory`: **0** production-попаданий; **6** тестовых вызовов `createMBeanServer`; **0** вызовов `newMBeanServer` | `PlatformExtensionReadinessJmxTest:19,36`; `PlatformSpanProcessorFactoryWiringTest:45,59`; `AgentExtensionObserverTest:28,48,91` | `FACT` |
| **11** мутирующих методов в `PlatformTracingJmxClient` | Строки 165, 171, 177, 183, 189, 254, 290, 302, 353, 438, 444 | `FACT` |
| `RuntimeConfigApplier` — **не атомарен по построению**: 5 домена / 6 вызовов `runDomain`, каждый в своём `try/catch`, `PARTIAL` — полноправный исход со счётчиком `partialApplyCount` | Строки 78-99, 106-110, 139-148; Javadoc:30-31 «Best-effort домен-изоляция» | `FACT` |
| Его availability-gate рассогласован с записями: требует **6** MBean, включая `PROCESSOR_METRICS` (у которого **ноль** мутирующих операций), при **5** записываемых доменах; Javadoc:26 говорит «шести доменных MBean» | Строки 69-73; `PlatformTracingJmxClient:56-63` | `FACT` |
| `TracingRefreshScopeAutoConfiguration` — **полностью ungated**: единственное условие `@ConditionalOnClass(RefreshScope.class)`; `POST /actuator/refresh` мутирует живое состояние агента | Строки 35-38, 55-62 | `FACT` |
| Ровно **1** `@WriteOperation` во всём репозитории, gated `platform.tracing.actuator.mutation-enabled` (default `false`) | `TracingActuatorEndpoint:202-204`, `assertMutationAllowed:298-305`; `TracingProperties:228` | `FACT` |
| **Расхождение control-protocol подтверждено и хуже заявленного:** валидатор распознаёт **16** ключей, applier применяет **2** домена; **8 из 16** возвращают `SUCCESS`, будучи молча отброшенными | `RuntimePolicyControlDomainValidator.RUNTIME_POLICY_KEYS:42-58`; `JmxRuntimePolicyApplier.apply:67-68` вызывает только `applySamplingFields` и `applyValidationFields`; отброшены `scrubbing.enabled`, `scrubbing.mode`, `scrubbing.ruleNames`, `enriching.enabled`, `export.enabled`, `propagation.enabled` + не читаются `sampling.qaTrace.enabled`, `sampling.forceHeader.enabled` | `FACT` |
| Наиболее серьёзное следствие: `applyPolicy` c `scrubbing.enabled=false` возвращает **SUCCESS**, тогда как скрабинг остаётся включённым | `RuntimePolicyControlHandler:98` `success(operation)`; оба helper'а `return` рано | `FACT` — приватность-релевантный silent-success |
| `PerfAdminController` находится в `platform-tracing-perf-tests`, **не** в `platform-tracing-perf-harness`; 3 полностью ungated мутирующих HTTP-эндпоинта; **все 4 hardcoded ObjectName устарели** (`type=SamplingControl` против публикуемого `type=Sampling` и т. д.), поэтому эндпоинты мертвы и маскируются под «extension not registered» | Строки 31-38, 43, 52, 63, 87, 115, 141-143 | `FACT` |
| `PlatformExtensionCapability` = **7** констант; `CONFIG_SNAPSHOT_V1` отсутствует (0 попаданий по репозиторию) | `readiness/PlatformExtensionCapability.java:6-14` | `FACT_ABSENCE` |
| Baseline JMX отсутствует; `DomainMBeanJmxComplianceTest` покрывает 6 из 71 члена; `ObjectNameConsistencyTest` не существует | §2.2 | `FACT_ABSENCE` |

**Число путей мутации.** Fable и досье заявляют «шесть». Проверка подтверждает шесть **семейств**, но с уточнением состава: (1) ungated листенер RefreshScope → `RuntimeConfigApplier`; (2) gated Actuator `@WriteOperation` (5 селекторов); (3) частичный control protocol `applyPolicy` — единственный gated JMX-путь, применяющий 2 из 6 распознаваемых доменов; (4) **15 ungated прямых доменных JMX-мутаторов** — на практике основной production-путь; (5) 3 ungated HTTP-эндпоинта `PerfAdminController`, все мертвы из-за устаревших ObjectName; (6) мутация из JMH/тестов, включая `RuntimeSamplingControlSmokeMain` `POST /admin/sampling-ratio:112` и `POST /admin/shutdown:124`.

**Три взаимно независимых gate без общей политики** (`FACT`): `platform.tracing.control.runtime-mutation.enabled` (agent-side, покрывает 1 из 16), `platform.tracing.actuator.mutation-enabled` (Spring-side, 1 эндпоинт), и **ничего** для листенера RefreshScope и 15 прямых мутаторов.

### R-05 — Семантика `MBeanServerFactory` на Java 21

Все 13 наблюдений §5.4. Метка: `FACT` (исполнено).

### R-06 — Стек зависимостей

Все пункты §5.3. Наиболее существенно: `platform-tracing-bom/build.gradle:29` транслирует `spring-cloud-context:4.1.5` как **`api`**-констрейнт; 4.1.x принадлежит EOL-поезду 2023.0.x, целящемуся в Boot 3.2/3.3, при закреплённом Boot 3.5.5. Метка: `FACT` (репозиторий) + внешняя матрица (уровень 6).

---

## 7. Ошибки членов совета и вытесненные утверждения

| # | Утверждение | Источник | Статус | Опровержение |
|---|---|---|---|---|
| E-01 | «Артефакты Step 0B отсутствуют в репозитории», «глобальный поиск… 0 совпадений», «`build.gradle` — ноль упоминаний `configProvenance`» | Fable F-01; GPT §1 | **MODEL_ERROR по факту, верно по выводу** | Файлы существуют в working tree (§2.1). Вывод о невоспроизводимости верен по иной причине: они не отслеживаются git |
| E-02 | Step 0B трактуется как подтверждённый; `./gradlew :platform-tracing-e2e-tests:test --tests "ConfigServerProvenanceCharacterizationTest"` приводится как рабочая команда | Gemini §Executive, §Verification Commands | **MODEL_ERROR** | Тест находится в source set `configProvenance`, а не `test`; указанная команда его не запустит. Корректный task — `configProvenanceTest`, и он не закоммичен |
| E-03 | «Gate E3 закрыт: `RETAIN_GENERIC` подтверждён кодом» | Fable D-05 | **MODEL_ERROR** | Условие реверсии №3 отчёта 04 §15 выполнено фактически: `STALE`, `REJECTED`, `FAILED` неразличимы (§5.1 Q4) |
| E-04 | «Expected-base CAS невыразим; API не имеет expected-reference/sequence one-shot операции» как основание для `REFACTOR_DOMAIN_SPECIFIC` | GPT D5 | **Частично неверно** | Семантика **выразима** через null-кандидата (§5.1 Q2). Верный аргумент GPT — не выразимость, а неклассифицируемость исхода |
| E-05 | «Use `newMBeanServer` over `createMBeanServer`» при одновременном требовании Spring-discovery | Gemini Table 7 + Phase 1 | **MODEL_ERROR, внутренне противоречиво** | Наблюдение 4: `newMBeanServer` не обнаруживается фабрикой (§5.4) |
| E-06 | «`67` фактических имён; «64» в досье — недосчёт» | Fable F-02, D-10 | **MODEL_ERROR** | Точное число — **78** (§2.3). Оба счёта игнорируют `PropagationDefaults` и `PlatformResourceProvider` |
| E-07 | «64-key migration map», «64 current tracing properties exist» | Досье; исправленные 01, 02, 06; GPT D10 | **SUPERSEDED_BY_REPOSITORY** | То же |
| E-08 | GPT D10 сам отмечает противоречие: «the source-backed inventory document says 64 but its own tables enumerate 36+31» | GPT D10 | **Корректная частичная детекция** | GPT единственный заметил внутреннее противоречие, но остановился на 36+31 и не расширил инвентарь |
| E-09 | Category A включает `policy.scrubbing.built-in-rules[]` | Fable D-02 | **REJECT** | Нарушает прямой запрет постановки на композицию pipeline; экстраординарных доказательств не предъявлено; §5.2 |
| E-10 | Category A = 5 ключей; `validation.*` и `propagation.*` целиком в Category B | Gemini D-02, D-03 | **REJECT как необоснованное сужение** | `validation.enabled/strict` мутируемы сегодня, имеют названных SRE-потребителей, низкий риск и startup-огибающую; §5.2 |
| E-11 | Авторитет — строгая иерархия `CONFIG_SERVER > DEV_ACTUATOR > HELM` | Gemini D-09 | **MODEL_ERROR** | Отчёт 06 §1.3 и оба других члена: `mode` и `authority` **ортогональны**; авторитет управляет только `policy.*` |
| E-12 | `InvalidConfigCount` → «KEEP (same semantics)» | Gemini Table 4 | **Вытеснено** | Отчёт 09 и GPT: `SPLIT/REWRITE` после инвентаризации producer'ов; текущая семантика смешивает decode/bootstrap/reject |
| E-13 | D-11 «Deletion consequence: N/A» для стратегии зависимостей | Gemini | **Неполно** | Пропускает дефект EOL-BOM `api`-констрейнта (§5.3) |
| E-14 | `PerfAdminController` в `platform-tracing-perf-harness` | Формулировки нескольких документов | **Неверно** | Модуль — `platform-tracing-perf-tests` |
| E-15 | Целевое имя `platform.tracing.policy.version` (Gemini Table 2 не указывает, но целевой namespace `policy.*` подразумевает) | Планируемая таксономия | **CONFLICT** | Имя уже занято resource-атрибутом `RESOURCE_POLICY_VERSION_ATTR` (§2.3) |
| E-16 | GPT §6.9: «эти имена task'ов существуют в checkout… nonexistent `configProvenanceTest` намеренно не указан» | GPT | **Частично неверно, но добросовестно** | `configProvenanceTest` существует в working tree и исполним; GPT корректно отказался его указывать, исходя из своих наблюдений |

**Соблюдённые запреты.** Ни отчёт Fable, ни отчёт GPT не скопирован как итоговый план. Решения по C-01, C-04, C-05, C-06 приняты вопреки простому большинству: C-01 — против 2:1 за `RETAIN_GENERIC` (объявлен спайк, а не победитель); C-04 — против всех трёх списков; C-06 — против Gemini, но с добавлением четырёх свойств, упущенных остальными двумя. Точные LOC, счётчики файлов и свойств не изобретались: каждое число выведено из кода в этой сессии или помечено как утверждение источника.

---

## 8. Обязывающие доказательные гейты

Гейты унаследованы от отчётов 10/11/12 (E1–E8) и приведены в соответствие с фактами этой сессии. Ни один не закрыт.

| Гейт | Требование | Состояние после арбитража | Спайк |
|---|---|---|---|
| **E1** | Baseline JMX-контракта + инвентарь названных потребителей | **OPEN.** `FACT_ABSENCE` подтверждено (§2.2). Область расширена: 71 член, 16 мутирующих, дубликат таблицы имён на Spring-стороне, отсутствующий `ObjectNameConsistencyTest` | предусловие S-03 |
| **E2** | Осуществимость и изоляция приватного process-local `MBeanServer` | **Частично закрыт JDK-спайком.** Механизм разрешён (§5.4); четыре свойства доказаны, два переклассифицированы (дубликаты разрешены JDK; release ≠ shutdown). Внутрирепозиторное, cross-classloader, многоконтекстное подтверждение остаётся открытым | **S-03** |
| **E3** | Точный API / прототип `VersionedStateHolder` | **OPEN и не пройден.** Условие реверсии №3 отчёта 04 §15 выполнено фактически. Победитель не назначен | **S-01** |
| **E4** | Полная точная disposition свойств и переменных окружения | **OPEN, база доказательств заменена.** Счёт 64/67 вытеснен точным 78 + 27; выявлены коллизия namespace, два семейства baggage, расхождение написаний, битый опубликованный контракт, 5 мёртвых константных читателей | **S-04** |
| **E5** | Финальная Category A + границы и классификация секретов | **OPEN.** 8 INCLUDE, 2 к решению, 2 EXCLUDE (§5.2) | **S-05** |
| **E6** | Production Boot/Cloud/Config Client + механизм доставки refresh | **OPEN + обнаружен независимый дефект.** EOL межпоездный `api`-констрейнт в публикуемом BOM (§5.3) | **S-06** |
| **E7** | Точная дельта JMX/диагностики/secret-safe проекции | **OPEN**, блокирован E1 | после E1 |
| **E8** | Явный implementation GO архитектурного комитета | **NOT_GRANTED** | — |
| **E9** *(новый)* | Воспроизводимость Step 0B: source set и T1–T10 под контролем версий, исполнены, отчёт сохранён на утверждённом стеке-кандидате | **OPEN.** `PRESENT_BUT_UNTRACKED` (§2.1) | **S-02** |

Введение E9 — прямое следствие §2.1: прежняя формулировка E6 предполагала, что доказательство provenance существует. Оно существует физически, но не воспроизводимо, поэтому требует собственного гейта, а не растворения в E6.

---

## 9. Требуемые спайки

Полные определения — в `13b-post-council-repository-spike-plan.md`. Сводка и порядок:

| Спайк | Предмет | Гейт | Блокирует |
|---|---|---|---|
| **S-02** | Восстановление/воспроизведение Step 0B (commit + исполнение + сохранение отчёта) | E9 | Все выводы о Config Server; отклонение атаки A-04 |
| **S-01** | Сравнение примитива состояния: A (`VersionedStateHolder` + one-shot) против B (`AcceptedTracingPolicyStateStore`) | E3 | Всю реализацию состояния |
| **S-03** | Приватный `MBeanServer` внутри репозитория, cross-classloader, многоконтекстно, DISABLED, shutdown | E1+E2 | Границу записи; удаление legacy |
| **S-04** | Генерируемый инвентарь свойств и переменных окружения, без wildcard-строк | E4 | Миграцию namespace; схему |
| **S-05** | Пакет решения по Category A для SRE и security | E5 | Схему wire; заморозку Category A |
| **S-06** | Матрица зависимостей production Config Client + consumer-smoke | E6 | Governance-одобрение; публикацию BOM |

**Рекомендуемый порядок исполнения:** `S-02` первым — не потому, что он самый рискованный, а потому, что он самый дешёвый и разблокирует наибольшее число уже сделанных выводов. Файлы существуют, стек уже разрешается, Gradle уже видит source set; требуется коммит, исполнение и сохранение артефакта. Далее `S-01` и `S-03` параллельно (независимы), затем `S-04` → `S-05`, и `S-06` параллельно всему остальному, так как это governance-трек.

---

## 10. Сознательно отложенные решения

| # | Решение | Почему отложено | Условие рассмотрения |
|---|---|---|---|
| D-01 | Победитель C-01 (F1 против F2) | Доказательств достаточно для непрохождения гейта, недостаточно для назначения победителя | Обязывающий критерий S-01 |
| D-02 | Финальный полный список Category A | Два листа требуют внешних решений | S-05 + SRE + security |
| D-03 | Механизм доставки refresh в production (Bus+Monitor против защищённого `/actuator/refresh` против корпоративного оркестратора) | Операционное решение, не архитектурное; репозиторных доказательств нет ни за один вариант | Runbook + SRE |
| D-04 | Точный корпоративный patch-level Boot (3.5.5 против 3.5.6) | Определяется корпоративной политикой поддержки, не спайком | S-06 + governance |
| D-05 | Точная дельта членов JMX (какие read-атрибуты сохраняются) | Требует baseline, которого нет | E1 → S-03 → E7 |
| D-06 | Разрешение коллизии `platform.tracing.policy.version` | Требует полного инвентаря, чтобы увидеть все затронутые читатели | S-04 |
| D-07 | Судьба двух семейств baggage-ключей и 5 никогда не вызываемых outbound-читателей | То же | S-04 |
| D-08 | Целевая форма `resetSamplerCounters` (сохранить как диагностический или удалить) | Требует названного потребителя из baseline | E1 |
| D-09 | Подписанные snapshot'ы | Отвергнуты как неоправданные без отдельного signing authority; условие не выполнено | Появление независимого signer'а |
| D-10 | Отдельный полный LKG-payload | Отвергнут; условие реверсии (транзиентное состояние, не являющееся последним принятым) не одобрено | Одобрение такого состояния |

---

## 11. Требуемые входы от SRE, security и governance

### 11.1 SRE

| # | Запрос | Что решается | Последствие отказа |
|---|---|---|---|
| SRE-1 | Требуется ли `policy.sampling.drop-paths` как runtime-мутируемый? | Category A INCLUDE/EXCLUDE | Переходит в Category B; изменение требует restart |
| SRE-2 | Названный инцидентный сценарий для каждого из 8 листьев INCLUDE | Обоснование ширины Category A | Немотивированные листья переходят в B |
| SRE-3 | Названный потребитель для `resetSamplerCounters` и для каждого per-domain read-атрибута, планируемого к удалению | Дельта E1/E7 | Удаление без названного потребителя |
| SRE-4 | Выбор механизма доставки refresh в production и владелец fan-out | D-03 | Config Server сводится к rollout-only |
| SRE-5 | Приемлемость ёмкости audit-ring в 32 записи на процесс | S-09 | Требуется отдельная compliance-система |
| SRE-6 | Подтверждение, что стандартных health-статусов достаточно (без `DEGRADED`) | S-10 | Отдельное одобрение нового статуса |

### 11.2 Security

| # | Запрос | Что решается | Последствие отказа |
|---|---|---|---|
| SEC-1 | Допустим ли `policy.sampling.force-record-values` как runtime-мутируемый, учитывая вектор усиления через `X-Trace-On`? | Category A INCLUDE/EXCLUDE | Переходит в Category B |
| SEC-2 | Подтверждение исключения `scrubbing.built-in-rules` и `scrubbing.enabled` из Category A | §5.2 | — |
| SEC-3 | Полный инвентарь секретов: подтвердить, что `platform.tracing.scrubbing.hmac-key` — единственный сырой секрет, и что `scrubbing.hash.key-id` не секрет | U-13 | Расширение схемы исключений |
| SEC-4 | **Немедленно, вне зависимости от GO:** оценка того, что `applyPolicy` возвращает `SUCCESS` на запрос отключения скрабинга, ничего не применяя (R-04) | Приватность-релевантный silent-success в текущем коде | — |
| SEC-5 | **Немедленно, вне зависимости от GO:** оценка того, что 15 из 16 мутирующих JMX-операций ungated и удалённо вызываемы на платформенном сервере (R-04 + §5.4 наблюдение 13) | Обход в текущем коде | — |
| SEC-6 | Оценка ungated листенера RefreshScope: `POST /actuator/refresh` мутирует живую политику агента без какого-либо gate | Обход в текущем коде | — |
| SEC-7 | Одобрение мультигейтовой posture DEV_ACTUATOR и требования авторизации приватного writer'а | U-09, E2 | — |

### 11.3 Governance / архитектурный комитет

| # | Запрос | Что решается |
|---|---|---|
| GOV-1 | **Немедленно:** одобрить исправление публикуемого BOM — `spring-cloud-context:4.1.5` в области `api` принадлежит EOL-поезду 2023.0.x при закреплённом Boot 3.5.5 (§5.3) |
| GOV-2 | Выбор корпоративного patch-level Boot и поезда Cloud; согласованное обновление `docs/SUPPORTED.md:42,49` и BOM |
| GOV-3 | Политика зависимости Config Client: подтвердить, что starters не тянут её транзитивно |
| GOV-4 | Одобрение переименования/удаления 78 config-имён и 27 env-имён без алиасов |
| GOV-5 | Одобрение исправления `docs/contracts/public-contract-inventory.yaml:704` — опубликованный контракт указывает на несуществующий ключ |
| GOV-6 | Явный implementation GO (E8) — **не выдан** |
| GOV-7 | Явный legacy deletion GO — **не выдан** |

---

## 12. Условия финального арбитража

Финальный арбитраж может быть назначен, когда **все** пункты выполнены и подтверждены закоммиченными артефактами:

```text
1.  S-02 пройден: source set и T1–T10 закоммичены, исполнены на утверждённом
    стеке-кандидате, отчёт сохранён в репозитории; результат каждого из T1–T10
    зафиксирован как PASS/FAIL, а не как проза.
2.  S-01 пройден: обязывающий критерий выигрыша применён к исполняемым
    результатам; вариант A или B выбран доказательством, не предпочтением.
3.  S-03 пройден: восемь свойств приватного writer'а доказаны внутри
    репозитория, включая предотвращение дублирования и разделение
    unregister/release.
4.  E1 закрыт: baseline JMX-контракта закоммичен и contract-тест допускает
    только одобрённую дельту; ObjectNameConsistencyTest существует либо
    дубликат таблицы имён устранён.
5.  S-04 пройден: disposition-таблица, одна строка на точное имя, без wildcard;
    коллизия platform.tracing.policy.version разрешена; оба семейства baggage
    и расхождение написаний force-record закрыты.
6.  S-05 закрыт: SRE-1 и SEC-1 получили ответ; финальная Category A заморожена
    с границами и классификацией секретов.
7.  S-06 закрыт: GOV-1 исправлен; матрица зависимостей и consumer-smoke
    пройдены; SUPPORTED.md и BOM обновлены согласованно.
8.  SEC-4, SEC-5, SEC-6 рассмотрены: три обхода, существующие в текущем коде,
    получили решение (исправить сейчас либо принять риск до cutover).
9.  Ни один новый Gradle-модуль не создан; активных проектов остаётся 16.
10. Ни один compatibility-алиас не введён.
```

Пункты 8 и 10 добавлены арбитражем: обходы SEC-4/5/6 существуют **сегодня**, в pre-production сборке, независимо от целевой архитектуры, и не должны ждать implementation GO.

---

## 13. Соблюдение запретов

| Запрет | Соблюдение |
|---|---|
| Новый общий отраслевой обзор | Не проводился. Единственный внешний запрос — точечная проверка официальной матрицы поездов Spring Cloud для C-05 |
| Решение по большинству моделей | Не применялось. C-01, C-04, C-05, C-06 решены против простого большинства либо оставлены открытыми |
| Копирование отчёта Fable как финального плана | Не сделано; шесть его утверждений опровергнуты (§7) |
| Копирование отчёта GPT как финального плана | Не сделано; два его утверждения скорректированы (§7) |
| Скрытие разногласий | Не сделано; §5 и §7 приводят все позиции дословно |
| Трактовка `READY_FOR_IMPLEMENTATION_GO_REVIEW` как обязывающего | Не сделано (§2.5) |
| Трактовка таблиц Fable как истины кода без проверки | Не сделано; каждое число перепроверено |
| Изобретённые LOC / файловые / свойственные счётчики | Не сделано; все числа выведены из кода в этой сессии |
| Новый Gradle-модуль | Не создан |
| Compatibility-алиасы | Не введены |
| Общий configuration-framework | Не предложен |
| Сохранение старого и нового writer'а в опубликованном артефакте | Запрещено; U-16 |
| GO на основании наличия прозаических ответов на 22 вопроса | Отказано явно (§1.2) |
| Модификация production-кода | **0 изменений.** Единственные артефакты, созданные этой задачей: настоящий документ, `13b`, и два одноразовых JDK-спайк-файла в системном temp-каталоге вне всех трёх workspace |

---

## 14. Финальный чеклист состояния решений

```text
Architecture direction:
    accepted

State primitive:
    spike required            (S-01; Gate E3 не пройден, победитель не назначен)

Private writer:
    spike required            (S-03; механизм createMBeanServer + marker
                               ПРИНЯТ по исполняемому JDK-спайку;
                               внутрирепозиторное подтверждение открыто)

Config Server evidence:
    historical-only           (PRESENT_BUT_UNTRACKED: код существует в working
                               tree, не под контролем версий, артефактов
                               исполнения нет; воспроизводим только стек)

Property inventory:
    incomplete                (точное число получено — 78 config + 27 env,
                               вытесняя 64 и 67; disposition-таблица не создана,
                               коллизия namespace не разрешена)

Category A:
    pending SRE/security      (8 INCLUDE, 2 EXCLUDE,
                               drop-paths -> SRE, force-record-values -> security)

JMX baseline:
    absent

Production dependency strategy:
    pending governance        (плюс независимый дефект: EOL межпоездный
                               api-констрейнт spring-cloud-context:4.1.5
                               в публикуемом BOM)

Implementation GO:
    NOT_GRANTED

Legacy deletion GO:
    BLOCKED
```

---

## Приложение A — Таблица извлечения консенсуса

Одна строка на существенное решение. `—` означает, что источник вопрос не рассматривает.

| Решение | Fable 5 | Gemini 3.1 Pro | GPT-5.6 SOL | Исправленные анализы | Репозиторные доказательства | Статус консенсуса | Обязывающий статус |
|---|---|---|---|---|---|---|---|
| Agent-first production topology | Да | Да | Да | 01–06, 10 | `FACT` — `SUPPORTED.md:20,42` | UNANIMOUS | ACCEPT |
| Нет production-паритета Local SDK | Да | Да | Да | 02 §C1, 03, 06 | `FACT` — `sdk.mode` мёртв | UNANIMOUS | ACCEPT |
| Ни одного нового Gradle-модуля | Да (16→16) | Да | Да (16) | 01, 03, 06 | `FACT` — 16 `include` | UNANIMOUS | ACCEPT |
| Один Agent-side владелец принятой политики | Да | Да | Да | 04, 10, 11 | `PLAN`; сегодня 6 владельцев | UNANIMOUS | ACCEPT |
| Одна агрегатная граница записи | Да | Да | Да | 01 §2.4, 02 §C5, 05 | `PLAN`; сегодня 16 мутирующих op | UNANIMOUS | ACCEPT |
| Приватный process-local writer | `createMBeanServer` | `newMBeanServer` | `createMBeanServer` | 08 §2.2, 12 A-11 | `FACT` — спайк: только `createMBeanServer` обнаруживается | SPLIT → разрешено | ACCEPT механизма + SPIKE_REQUIRED |
| Платформенный MBeanServer read-only после cutover | Да | Да | Да | 08, 09 §5.1 | `PLAN`; сегодня write-op удалённо вызываема | UNANIMOUS | ACCEPT; удаление блокировано E1 |
| Config Server — авторитет желаемого состояния | Да | Да | Да | 01 §3.1, 05 | `FACT_ABSENCE` в production | UNANIMOUS | ACCEPT + GOVERNANCE_REQUIRED |
| DEV_ACTUATOR — non-production, JMX-only | Да | Да | Да | 07, 06 | `PLAN` | UNANIMOUS | ACCEPT |
| Нет HTTP-мутации в цели | Да | Да | Да | 02, 03, 05, 07 | `FACT` — 1 `@WriteOperation` | UNANIMOUS | ACCEPT |
| Нет прямых доменных JMX-сеттеров после cutover | Да | Да | Да | 07 §15, 10 §4 | `FACT` — 16 сегодня | UNANIMOUS | ACCEPT; блокировано E1 |
| `scrubbing.rules-config` startup-only | Да | Да | Да | 01 §2.6, 02 §C7, 04, 06, 08 | `FACT` — `ScrubbingRulesLoader` | UNANIMOUS | ACCEPT |
| Нет сырых секретов в snapshot/digest/audit/log | Да (SC-01…16) | Да | Да | 08, 09 | `FACT` — `hmac-key` существует | UNANIMOUS | ACCEPT + SECURITY_APPROVAL_REQUIRED |
| Полный snapshot, не patch | Да | Да | Да | 01, 05 §312 | `PLAN`; T9/T10 `UNVERIFIED` | UNANIMOUS | ACCEPT |
| Нет compatibility-алиасов | Да | Да | Да | 01, 02, 06 | политика pre-production | UNANIMOUS | ACCEPT |
| Нет released dual-архитектуры | Да | Да | Да | 10 §1.1 | — | UNANIMOUS | ACCEPT |
| Связный неопубликованный cutover-train | Да (T1–T7) | Да (4 фазы) | Да (P0–P7) | 10 §3 | — | UNANIMOUS | ACCEPT |
| Текущее принятое состояние — логический LKG | Да | Да | Да | 03, 04 §9, 09 §2 | `FACT` — CAS-промах сохраняет предыдущее | UNANIMOUS | ACCEPT |
| Нет отдельного полного LKG-payload | Да | Да | Да | 04 §468, 09 §2 | — | UNANIMOUS | ACCEPT |
| **Примитив состояния** | RETAIN_GENERIC + `compareAndSet`; «E3 закрыт» | RETAIN_GENERIC, без API-сравнения | REFACTOR_DOMAIN_SPECIFIC | 04 RETAIN_GENERIC при условии review; 11 F1 531/640; 12 «не фальсифицирован» | `FACT` — retry-loop; исходы неразличимы; 0 потребителей после удаления; версия дублирована в 8 местах | **SPLIT** | **SPIKE_REQUIRED** (S-01) |
| **Доказательство Step 0B** | ABSENT | Подтверждено | ABSENT | 01, 02, 05 — PASS | `FACT_UNTRACKED` — есть в дереве, нет в git, нет артефактов исполнения | **SPLIT, все три неверны** | **SPIKE_REQUIRED** (S-02) |
| **Число ключей** | 67 (36+31) | 64 | 64 + 3 supplemental, отмечает противоречие | 64 повсеместно | `FACT` — **78** config + 27 env | **MODEL_ERROR у всех** | **SUPERSEDED_BY_REPOSITORY** |
| **Ширина Category A** | 11, вкл. scrubbing rules | 5 | 10 | не заморожена ни одним | `FACT` — 8 INCLUDE, 2 к решению, 2 EXCLUDE | **SPLIT** | **SRE_APPROVAL_REQUIRED + SECURITY_APPROVAL_REQUIRED** (S-05) |
| **Production-стек Cloud** | 3.5.6 не обязателен; поезд 2025.0.x | Config Client — прикладная зависимость; «N/A» | 3.5.6 + BOM 2025.0.0 + 4.3.0; убрать 4.1.5 | 05 — governance-гейт; 3.5.6 не обязателен | `FACT` — BOM транслирует 4.1.5 как `api`; 4.1.x = EOL 2023.0.x | **SPLIT + дефект пропущен всеми** | **GOVERNANCE_REQUIRED** (S-06) |
| Модель авторитета (ортогональность) | Ортогональна | **Строгая иерархия** | Ортогональна | 06 §1.3 — ортогональна | `PLAN` | STRONG_CONSENSUS | ACCEPT (Gemini — MODEL_ERROR) |
| `CONFIG_SNAPSHOT_V1` в capability | Да | Да | Да | 05, 07, 09 | `FACT_ABSENCE` — enum = 7 констант | UNANIMOUS | ACCEPT |
| Audit-ring 32 записи, метаданные | Да | Да (32) | Да (32) | 09 | `PLAN` | UNANIMOUS | ACCEPT + SRE-5 |
| Диагностика: 4 владельца, per-domain version/source удаляются | Да | Частично (`InvalidConfigCount` KEEP) | Да | 09 §3 | `PLAN`; дельта блокирована E1 | STRONG_CONSENSUS | ACCEPT направления |
| `sourceRevision` — метаданные, не порядок | Да | — | Да | 01 §2.5, 05 | T7/T8 `UNVERIFIED` | STRONG_CONSENSUS | ACCEPT |
| Structural drift: initial блокирует readiness, runtime → restartRequired | Да | Да | Да | 05, 13 | `PLAN` | UNANIMOUS | ACCEPT |
| Starters не тянут Config Client транзитивно | Да | Да | Да | 05 §712 | `FACT` — сегодня Config Client отсутствует | UNANIMOUS | ACCEPT |
| `PlatformHeaders` — единственный контракт имён заголовков | Да | — | Да | 06 §7 | `FACT` — конфигурируемые имена существуют сегодня | STRONG_CONSENSUS | ACCEPT |
| Baseline JMX до любого удаления | Да | Да (Phase 1) | Да (P0) | 01 §2.3, 07, 09, 10 | `FACT_ABSENCE` | UNANIMOUS | ACCEPT; гейт E1 OPEN |

---

## Приложение B — Прочитанные источники

**Исправленные анализы (уровень 3), все прочитаны полностью:** `01-external-industry-evidence.md` (`PASS_WITH_CORRECTIONS`), `02-plan-to-code-contradiction-audit.md` (`PASS_WITH_MAJOR_CORRECTIONS`), `03-radical-architecture-alternatives.md` (`PASS_WITH_MAJOR_CORRECTIONS`), `04-versioned-state-atomicity-decision.md` (`PASS_WITH_CRITICAL_CORRECTIONS`), `05-config-server-production-control.md` (`PASS_WITH_CRITICAL_CORRECTIONS`), `06-authority-namespace-migration.md` (`PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS`), `07-dev-actuator-jmx-design.md` (`PASS_WITH_CRITICAL_CORRECTIONS`), `08-security-trust-boundary-review.md` (`PASS_WITH_CRITICAL_SECURITY_AMENDMENTS`), `09-sre-diagnostics-jmx-contract.md` (`PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS`), `10-aggressive-cleanup-cutover-plan.md` (`PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS`), `11-architecture-multicriteria-scoring.md` (`PASS_WITH_CRITICAL_METHODOLOGY_CORRECTIONS`), `12-adversarial-architecture-falsification.md` (`PASS_WITH_BLOCKERS`).

**Отчёты совета (уровень 4):** `model-council-claude_fable_5.md` (`NOT_READY`), `model-council-gemini_3_1_pro.md` (`READY_FOR_IMPLEMENTATION_GO_REVIEW` — переклассифицирован в не обязывающий), `model-council-gpt_5_6_sol.md` (`NOT_READY`).

**Репозиторные доказательства (уровни 1–2):** `docs/research/config-server-property-provenance-spike.md`, `docs/research/hybrid-tracing-configuration-codebase-context.md`, `docs/research/tracing-properties-codebase-investigation.md`, `docs/SUPPORTED.md`, `docs/analysis/plans/immutable_config_namespace_split_ba8da442.plan.md`, плюс прямая инспекция source, build-файлов, тестов и git-состояния, перечисленная в §6.

**Внешняя документация (уровень 6), единственное использование:** официальная таблица совместимости поездов Spring Cloud и Spring Boot — только для внешнего факта в §5.3.
