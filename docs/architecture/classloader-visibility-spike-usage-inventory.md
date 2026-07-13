# ClassLoader Visibility Spike Usage Inventory

## 1. Executive Summary

**Что такое spike:** `ClassLoaderVisibilitySpikeProbe` — утилитарный final-класс в `src/main` модуля `platform-tracing-otel-extension`, который при явной активации через system property печатает машиночитаемые маркеры `SPIKE_CLASSLOADER:...` в `System.err`. Класс реализует два независимых gated-контракта: (a) `emitExtensionLoadResultIfEnabled` — сводка загрузки custom scrubbing rules из production-пути фабрики; (b) `runIfEnabled` — полный probe видимости `SpanAttributeScrubbingRule` через `ServiceLoader` в четырёх вариантах classloader.

**Где расположен:** `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/factory/spike/ClassLoaderVisibilitySpikeProbe.java`.

**Откуда вызывается в production:** единственный production call-site — `PlatformSpanProcessorFactory.collectScrubbingRules(...)`, строка ~163, сразу после `StartupDiagnostics.emit(...)`.

**Как активируется:** system property `platform.tracing.spike.classloader.visibility=true` (case-insensitive). Без неё — немедленный return, без stderr и без побочных эффектов на правила/процессоры.

**Работает ли при отключении:** да, как no-op: только чтение `System.getProperty` и сравнение с `"true"`; allocations, logging, `System.err` — отсутствуют.

**Какие тесты зависят:** основной E2E — `ClassLoaderVisibilitySpikeE2ETest` (модуль `platform-tracing-e2e-tests`); вспомогательный launcher `ClassLoaderVisibilitySpikeMain` (test-scope). Unit/integration тестов, напрямую вызывающих probe, в `platform-tracing-otel-extension/src/test/java` **не найдено**.

**Почему архитекторы обеспокоены:** production factory импортирует пакет `factory.spike`; в hot-path загрузки scrubbing rules присутствует test-only stderr-контракт; имя и комментарии явно маркируют «spike»/«spike-only»; ArchUnit-исключение для `factory.spike` от `ACCESS_STANDARD_STREAMS` фиксирует осознанное отступление от guardrail.

**Предлагает ли документ финальную архитектуру:** нет. Документ — инвентарь и вход для последующего Perplexity pass по 6 вариантам.

---

## 2. Scope

### Просмотренные модули

| Модуль | Путь | Статус |
|--------|------|--------|
| `platform-tracing-otel-extension` | `src/main/java`, `src/test/java` | просмотрен |
| `platform-tracing-e2e-tests` | `src/test/java`, `src/customRule/java` | просмотрен |
| `platform-tracing-e2e-tests` | `src/main/java` | **NOT FOUND IN REPOSITORY** (каталог отсутствует) |
| `platform-tracing-test` | `src/main/java`, `src/test/java` | просмотрен — ссылок на spike нет |
| `platform-tracing-spring-boot-autoconfigure` | `src/main/java`, `src/test/java` | просмотрен — ссылок на spike нет |
| `docs` | `docs/architecture`, `docs/decisions`, `docs/tracing` | просмотрен |
| `scripts` | `scripts/run-classloader-spike-gentoo.ps1` | просмотрен (вне обязательного списка, но релевантен E2E) |

### Выполненные поиски

```text
rg "ClassLoaderVisibilitySpikeProbe" .
rg "platform\.tracing\.spike\.classloader\.visibility|SPIKE_CLASSLOADER" .
rg "factory\.spike|\.spike\." platform-tracing-otel-extension/src/main/java platform-tracing-otel-extension/src/test/java platform-tracing-e2e-tests/src/test/java docs
rg "emitExtensionLoadResultIfEnabled" .
rg "ClassLoaderVisibilitySpikeE2ETest|classloader visibility|classloader-visibility|visibility spike" .
rg "System\.err|stderr|error stream|process output|SPIKE_CLASSLOADER" .
rg "otel\.javaagent\.extensions|OTEL_JAVAAGENT_EXTENSIONS|javaagent.extensions" platform-tracing-otel-extension/src platform-tracing-e2e-tests/src docs
rg "ExtensionRuleLoader|ScrubbingRulesLoader|SpanAttributeScrubbingRule|customRules|loadingMode" platform-tracing-otel-extension/src platform-tracing-e2e-tests/src docs
```

### Git

```text
Git unavailable; used rg-based repository search.
```

(`git status` и `git grep` завершились `fatal: not a git repository`)

### Адаптация путей

- `platform-tracing-e2e-tests/src/main/java` — не существует; E2E launcher `ClassLoaderVisibilitySpikeMain` находится в `src/test/java`.
- Custom rule fixture — `platform-tracing-e2e-tests/src/customRule/` (отдельный Gradle sourceSet `customRule`).

---

## 3. Spike Class Inventory

| Class | Package | Source Root | Visibility | Public API | Purpose From Code | Risk |
|-------|---------|-------------|------------|------------|-------------------|------|
| `ClassLoaderVisibilitySpikeProbe` | `space.br1440.platform.tracing.otel.extension.factory.spike` | `platform-tracing-otel-extension/src/main/java` | `public final` | `ENABLE_PROPERTY`, `LINE_PREFIX`, `TARGET_RULE_NAME`, `TARGET_RULE_CLASS`, `emitExtensionLoadResultIfEnabled(int, String)`, `runIfEnabled()` | Gated stderr-диагностика видимости `SpanAttributeScrubbingRule` через `ServiceLoader` в OTel Java Agent runtime; маркеры для E2E-парсинга; «не влияет на production-поведение» (Javadoc класса) | Production factory импортирует `factory.spike`; `System.err` при enable; имя «Spike»; hardcoded E2E rule id `custom-e2e-rule` |

**Дополнительные spike-классы в `factory/spike`:** NOT FOUND IN REPOSITORY (единственный файл в каталоге).

**Связанные, но отдельные spike-активы (не ClassLoaderVisibility):**

| Class / area | Module | Note |
|--------------|--------|------|
| `..otel.extension.spike.*` (BSP, baggage) | otel-extension `src/test/java` | test-scope spikes, не связаны с ClassLoaderVisibility |
| ~~`..otel.extension.jmx.spike.*`~~ | **удалён из production** (NEW_HYBRID) | JMX wire spike транспорт убран из `src/main`; харнесс перенесён в `platform-tracing-e2e-tests/src/jmxWireExtension` (`WireRoundTripTest*`) как test-only OTel extension |

---

## 4. Production Call-Site Inventory

| File | Class | Method | Reference Type | Code Context | Runtime Effect |
|------|-------|--------|----------------|--------------|----------------|
| `PlatformSpanProcessorFactory.java` | `PlatformSpanProcessorFactory` | (import) | `import ...factory.spike.ClassLoaderVisibilitySpikeProbe` | строка 23 | compile-time зависимость production factory от spike-пакета |
| `PlatformSpanProcessorFactory.java` | `PlatformSpanProcessorFactory` | `collectScrubbingRules(ScrubbingExtensionConfig, ConfigProperties)` | method call | после `ExtensionRuleLoader.load(...)`, merge rules, `StartupDiagnostics.emit(...)`; комментарий «Spike-only stderr-маркеры для E2E»; строка ~163 | при `-Dplatform.tracing.spike.classloader.visibility=true` печатает `SPIKE_CLASSLOADER:customRules=N` и `SPIKE_CLASSLOADER:loadingMode=...`; иначе no-op |

**Другие production references:** NOT FOUND IN REPOSITORY.

**Полный production call path (proven):**

```text
Agent bootstrap → PlatformSpanProcessorFactory.registerSpanProcessors(...)
  → collectScrubbingRules(...)
    → ScrubbingRulesLoader.load (built-in names)
    → ServiceLoader (bundled SPI)
    → ExtensionRuleLoader.load(platform.tracing.scrubbing.rules.extensions)
    → mergeRules(...)
    → StartupDiagnostics.emit(...)  [slf4j INFO/WARN]
    → ClassLoaderVisibilitySpikeProbe.emitExtensionLoadResultIfEnabled(custom.size(), loadingMode)
    → return rules (unchanged by probe)
```

---

## 5. Activation Contract

| Activation Input | Value | Used By | Behavior |
|------------------|-------|---------|------------|
| System property `platform.tracing.spike.classloader.visibility` | `"true"` (case-insensitive) | `emitExtensionLoadResultIfEnabled`, `runIfEnabled` | probe выполняется, stderr-маркеры печатаются |
| System property `platform.tracing.spike.classloader.visibility` | absent, blank, any value ≠ `"true"` | оба метода | немедленный `return` |
| Environment variable | — | — | NOT FOUND IN REPOSITORY |
| `ConfigProperties` / OTel config | — | — | NOT FOUND IN REPOSITORY |
| Spring `@ConfigurationProperties` | — | — | NOT FOUND IN REPOSITORY |

**Чтение свойства:** на каждый вызов метода через `System.getProperty(ENABLE_PROPERTY)` — **без кэширования** (proven из кода).

**Константа в коде:** `ClassLoaderVisibilitySpikeProbe.ENABLE_PROPERTY = "platform.tracing.spike.classloader.visibility"`.

**E2E включает spike так:** `-Dplatform.tracing.spike.classloader.visibility=true` (через `ClassLoaderVisibilitySpikeProbe.ENABLE_PROPERTY + "=true"`).

---

## 6. Output Contract

### 6.1 Маркеры из `emitExtensionLoadResultIfEnabled` (factory path, до `main`)

| Marker / Output | Emitted When | Value Source | Consumed By |
|-----------------|--------------|--------------|-------------|
| `SPIKE_CLASSLOADER:customRules=N` | property=true, вызов из `collectScrubbingRules` | `custom.size()` после `ExtensionRuleLoader.load` | `ClassLoaderVisibilitySpikeE2ETest` (assert F3) |
| `SPIKE_CLASSLOADER:loadingMode=...` | property=true, тот же вызов | `loadingMode`: `"NONE"` если `rules.extensions` blank, иначе `"PLATFORM_RULES_EXTENSIONS"` | `ClassLoaderVisibilitySpikeE2ETest` (assert `PLATFORM_RULES_EXTENSIONS`) |

### 6.2 Маркеры из `runIfEnabled` (E2E main path, после agent init)

| Marker / Output | Emitted When | Value Source | Consumed By |
|-----------------|--------------|--------------|-------------|
| `SPIKE_CLASSLOADER:BEGIN` | property=true, `runIfEnabled()` | константа | `ClassLoaderVisibilitySpikeE2ETest` |
| `SPIKE_CLASSLOADER:END` | property=true, конец `runIfEnabled()` | константа | `ClassLoaderVisibilitySpikeE2ETest` |
| `SPIKE_CLASSLOADER:extensions=...` | property=true | `System.getProperty("otel.javaagent.extensions")` | E2E parser (косвенно) |
| `SPIKE_CLASSLOADER:tccl=...` | property=true | `Thread.currentThread().getContextClassLoader()` | E2E parser |
| `SPIKE_CLASSLOADER:factoryCl=...` | property=true | `PlatformSpanProcessorFactory.class.getClassLoader()` | E2E parser |
| `SPIKE_CLASSLOADER:apiCl=...` | property=true | `SpanAttributeScrubbingRule.class.getClassLoader()` | E2E parser |
| `SPIKE_CLASSLOADER:variant=...` | property=true | `default`, `tccl`, `factory`, `api` | `parseSpikeVariants` |
| `SPIKE_CLASSLOADER:loader=...` | property=true | effective classloader per variant | E2E parser |
| `SPIKE_CLASSLOADER:foundRules=...` | property=true | имена rules из `ServiceLoader` iteration | E2E parser |
| `SPIKE_CLASSLOADER:targetFound=true/false` | property=true | match `custom-e2e-rule` или `MyCustomE2eRule` FQCN | E2E assert F1 (`nativeVisible` must be false) |
| `SPIKE_CLASSLOADER:variantEnd=...` | property=true | имя variant | `parseSpikeVariants` |
| `SPIKE_CLASSLOADER:targetClass=...` | property=true, если target найден | class of matched rule | optional diagnostics |
| `SPIKE_CLASSLOADER:targetRuleClassLoader=...` | property=true, если target найден | rule class CL | optional diagnostics |
| `SPIKE_CLASSLOADER:apiClassIdentity=...` | property=true, если target найден | interface check | optional diagnostics |
| `SPIKE_CLASSLOADER:evaluateOk=...` | property=true, если target найден | `rule.evaluate(...)` result | optional diagnostics |
| `SPIKE_CLASSLOADER:serviceConfigurationError=...` | при `ServiceConfigurationError` | exception class | optional diagnostics |
| `SPIKE_CLASSLOADER:probeError=...` | при Throwable в variant | exception class | optional diagnostics |

**Формат строки:** `System.err.println(LINE_PREFIX + payload)` где `LINE_PREFIX = "SPIKE_CLASSLOADER:"`.

**Другие sinks:** slf4j/logging в probe — NOT FOUND IN REPOSITORY.

**StartupDiagnostics (рядом, не spike):** slf4j `[scrubbing] Инициализировано — builtInRules=..., customRules=..., loadingMode=...` — отдельный production-контракт, не парсится E2E spike-тестом.

---

## 7. Runtime Semantics

### 7.1 What happens when the spike property is disabled?

**Proven из кода:**

- `emitExtensionLoadResultIfEnabled`: первые строки — `if (!"true".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY))) return;`
- `runIfEnabled`: идентичный guard.
- Нет allocations кроме чтения property string; нет `System.err`; нет slf4j из probe.
- `collectScrubbingRules` продолжает нормальную загрузку правил и `StartupDiagnostics.emit` независимо от spike.

### 7.2 What happens when enabled?

**Factory path (`emitExtensionLoadResultIfEnabled`):**

- Вызывается во время agent bootstrap при сборе scrubbing rules (до входа в application `main`).
- Печатает ровно две строки stderr (плюс возможные newline): `customRules=<int>`, `loadingMode=<string>`.
- Значения: `customRules` = размер списка после `ExtensionRuleLoader.load`; `loadingMode` = `"NONE"` или `"PLATFORM_RULES_EXTENSIONS"`.

**Main path (`runIfEnabled`):**

- Вызывается из `ClassLoaderVisibilitySpikeMain.main` после полной инициализации agent.
- Печатает BEGIN → metadata → 4 variant blocks → END.
- Каждый variant итерирует `ServiceLoader<SpanAttributeScrubbingRule>` с разным classloader; ищет `TARGET_RULE_NAME` / `TARGET_RULE_CLASS`.

**E2E порядок (inferred из agent lifecycle + кода):** сначала маркеры `customRules`/`loadingMode` (factory, agent init), затем BEGIN/variants/END (main).

### 7.3 Does it affect span processor behavior?

**Proven:** нет. Probe вызывается после формирования `rules` list, перед `return rules`. Не изменяет `builtIn`, `bundledSpi`, `custom`, merge, validation mode, processors.

### 7.4 Does it affect production observability/logging?

| State | Production observability impact |
|-------|--------------------------------|
| Disabled (default) | Нулевой impact от probe. Штатная диагностика — `StartupDiagnostics` через slf4j. |
| Enabled | Дополнительный шум в `System.err` (не slf4j); потенциально виден в container/process logs. ArchUnit `ACCESS_STANDARD_STREAMS` не распространяется на `factory.spike` (явное исключение в javadoc `SafeBoundaryArchTest`). |

**Классификация:** gated test instrumentation / spike diagnostics, **не** production business behavior.

---

## 8. E2E / Test Dependency Map

| Test Class | Module | How It Enables Spike | What It Asserts | Why src/main Is Needed |
|------------|--------|----------------------|-----------------|------------------------|
| `ClassLoaderVisibilitySpikeE2ETest` | `platform-tracing-e2e-tests` | Запускает дочернюю JVM: `-javaagent:...`, `-Dplatform.tracing.spike.classloader.visibility=true`, `-Dotel.javaagent.extensions=...`, `-Dplatform.tracing.scrubbing.rules.extensions=...`, `-cp smoke.test.runtime.classpath`, main = `ClassLoaderVisibilitySpikeMain` | (F1) `targetFound` false для всех native ServiceLoader variants; (F3) stderr содержит `customRules=1` и `loadingMode=PLATFORM_RULES_EXTENSIONS`; output содержит `BEGIN` и `END` | Probe и factory должны выполняться внутри реального OTel Java Agent `ExtensionClassLoader`; класс probe упакован в agent extension JAR (`src/main`) |
| `ClassLoaderVisibilitySpikeMain` (launcher, не JUnit) | `platform-tracing-e2e-tests` `src/test/java` | Вызывает `ClassLoaderVisibilitySpikeProbe.runIfEnabled()` | Не assert'ит сам; печатает `READY` на stdout | Импортирует probe из main artifact; комментарий: «Probe вызывается здесь (не в фабрике)» для ServiceLoader variants |

**Gradle wiring (proven, `platform-tracing-e2e-tests/build.gradle`):**

- E2E только при `-PrunE2e`
- `systemProperty 'otel.javaagent.jar'`
- `systemProperty 'smoke.test.runtime.classpath'`
- `systemProperty 'smoke.otel.extension.jar'` → `agentExtensionJar`
- `systemProperty 'smoke.custom.rule.jar'` → `customRuleJar`
- `customRule` sourceSet → `MyCustomE2eRule` с SPI `custom-e2e-rule`

**stderr parsing:** `redirectErrorStream(true)` — stdout+stderr объединены; парсер ищет prefix `SPIKE_CLASSLOADER:`.

**Другие тесты, импортирующие probe:** NOT FOUND IN REPOSITORY (кроме E2E spike test/main).

**Unit tests в otel-extension для probe:** NOT FOUND IN REPOSITORY.

**Внешний скрипт (не JUnit):** `scripts/run-classloader-spike-gentoo.ps1` — Gentoo Docker cross-platform validation; парсит `SPIKE_CLASSLOADER:BEGIN`, `targetFound=true`.

---

## 9. Why It Is In Main Code Today

### Proven code/documentation facts

1. **ADR принят (Вариант B):** `docs/decisions/ADR-classloader-visibility-spike-finding.md` фиксирует spike E2E и probe; F1 — native `ServiceLoader` не видит sibling custom-rules JAR в `otel.javaagent.extensions`; F3 — custom rules через `platform.tracing.scrubbing.rules.extensions`.
2. **`ExtensionRuleLoader` Javadoc** ссылается на ADR и объясняет выделенный `URLClassLoader` для custom rules.
3. **`PlatformSpanProcessorFactory.collectScrubbingRules`** — production path загрузки custom rules; spike call привязан именно к этому методу.
4. **Probe class Javadoc:** «в runtime OpenTelemetry Java Agent», «для парсинга E2E-тестом», «не влияет на production-поведение».
5. **`emitExtensionLoadResultIfEnabled` Javadoc:** «Вызывается из фабрики при сборе правил (до main), чтобы E2E мог парсить stderr».
6. **E2E test Javadoc:** F1 native ServiceLoader + F3 post-refactor `platform.tracing.scrubbing.rules.extensions`.
7. **Migration inventory** (`platform-tracing-current-codebase-inventory.md`, preservation-first plan): spike помечен DEFER/ADAPT; E2E must stay green.

### Inferred architectural rationale

| Factor | Inference |
|--------|-----------|
| OTel Java Agent classloader isolation | Probe должен выполняться в том же CL-контексте, что и `PlatformSpanProcessorFactory` (`ExtensionClassLoader`), что недостижимо из test-only classpath без agent |
| ServiceLoader / extension JAR visibility | `runIfEnabled` проверяет 4 CL-варианта; доказательство F1 требует реального agent runtime |
| Custom `SpanAttributeScrubbingRule` loading | `emitExtensionLoadResultIfEnabled` подтверждает, что `ExtensionRuleLoader` загрузил rules через dedicated property (F3) |
| Test-scope-only difficulty | `ClassLoaderVisibilitySpikeMain` в test, но factory-path marker **обязан** жить в agent extension main code, т.к. factory вызывается до `main` |
| Documented vs implied | ADR и migration docs — explicit; отдельного `package-info` в `factory/spike` — NOT FOUND |

**Связь spike с доменом:** spike **привязан и к scrubbing custom rule loading** (factory call), **и к broader agent classloader concern** (ServiceLoader variants в `runIfEnabled`). Это не общий OTel probe — он специфичен для `SpanAttributeScrubbingRule` и platform extension factory.

---

## 10. Architecture / Fitness / Guardrail Mentions

| Rule / Doc | Mention | Meaning | Current? |
|------------|---------|---------|----------|
| `SafeBoundaryArchTest` | javadoc: `..factory.spike..` «намеренно исключены» из `ACCESS_STANDARD_STREAMS` | spike может писать в System.err; safe packages — нет | да (исходник в repo) |
| `ExtensionConfigBootstrapGuardrailsArchTest` | factory chain guardrails (без упоминания spike) | factory получает config через параметры | да; spike не упомянут |
| `platform-tracing-preservation-first-migration-plan.md` | `ClassLoaderVisibilitySpikeProbe` DEFER, PR-3 | spike asset, не мигрирован | да |
| `platform-tracing-current-codebase-inventory.md` | E2E ADAPT, probe inventory | учёт spike в migration | да |
| `ADR-classloader-visibility-spike-finding.md` | spike methodology + findings | архитектурное обоснование Варианта B | да (Accepted) |
| `pr-0-baseline-checklist.md` | команда запуска E2E | gate checklist | да |
| ~~`SpikeMBeanPropertyGateArchTest` / `jmx.spike`~~ | удалён (production spike purge) | заменён production-баном `SafeBoundaryArchTest.no_jmx_spike_package_in_production` | да, но другой spike |
| Explicit «production spike exception» ArchRule для `factory.spike` | — | NOT FOUND IN REPOSITORY (только javadoc-исключение в SafeBoundaryArchTest scope) |
| `package-info` в `factory/spike` | — | NOT FOUND IN REPOSITORY |

---

## 11. Architectural Smell Analysis

Почему архитекторы могут возражать (evidence-based):

1. **Импорт `factory.spike` в production factory** — единственный production call-site нарушает чистоту business code path.
2. **Имя «Spike» в `src/main`** — Javadoc и ADR называют это spike; воспринимается как временный research code.
3. **Комментарий в factory:** «Spike-only stderr-маркеры для E2E» — явная coupling test instrumentation ↔ production.
4. **`System.err` marker contract в main** — машиночитаемый stderr API в production artifact; обходит slf4j/observability pipeline.
5. **Hardcoded E2E identifiers** — `TARGET_RULE_NAME = "custom-e2e-rule"`, `TARGET_RULE_CLASS = "...MyCustomE2eRule"` в main code.
6. **ArchUnit carve-out** — необходимость исключать `factory.spike` из standard-streams rule сигнализирует об исключении из норм.
7. **Dual activation surface** — factory emit + main `runIfEnabled`; два контракта в одном E2E тесте усложняют migration.
8. **Accidental enablement risk** — property name публичный; при `-Dplatform.tracing.spike.classloader.visibility=true` в prod-like env появится stderr noise (не меняет rules, но засоряет logs).
9. **DEFER в migration plan** — spike не закрыт архитектурно, остаётся technical debt.
10. **ADR устаревшая ссылка** — ADR упоминает `appendSpiRulesFromExtensions()`; текущий код использует `ExtensionRuleLoader` (рефакторинг после spike findings) — документационный drift.

---

## 12. Constraints For Future Architecture Variants

Жёсткие ограничения для Perplexity architecture pass:

1. E2E classloader visibility tests must remain meaningful (F1 + F3 coverage).
2. Production business code should not import a `spike` package (целевое требование архитекторов).
3. Production runtime behavior must remain unchanged when property is disabled (no-op today).
4. Stderr markers may need compatibility or deliberate replacement (`SPIKE_CLASSLOADER:*` contract).
5. No Spring dependency in `otel-extension` (существующий ArchUnit guard).
6. No JMX/control-plane expansion unless explicitly chosen.
7. No weakening of E2E coverage (`ClassLoaderVisibilitySpikeE2ETest` green under `-PrunE2e`).
8. No hidden always-on diagnostics.
9. No reflection hacks unless justified with evidence.
10. No codegen/new module unless justified.
11. Custom rule loading via `platform.tracing.scrubbing.rules.extensions` must remain covered (Variant B ADR).
12. Probe must remain verifiable under real `-javaagent` + `ExtensionClassLoader` if classloader claims are preserved.
13. `StartupDiagnostics` slf4j path must not be broken for production.
14. Do not change system property name/markers in this inventory pass (future variants may propose migrations).
15. `ExtensionRuleLoader` JAR hygiene / duplicate `otel.javaagent.extensions` detection must stay intact.

---

## 13. Possible Architecture Axes For Later Scoring

Нейтральные оси (без выбора победителя):

1. Keep probe in main but rename/reclassify from `spike` to `diagnostics`.
2. Move to `scrubbing.diagnostics` package with explicit test probe contract.
3. Introduce small package-private test hook interface invoked only via reflection from E2E fixture (high scrutiny).
4. Use existing `StartupDiagnostics` as the only E2E signal for F3; relocate F1 ServiceLoader probe to test-only agent extension.
5. Move probe entirely to e2e test agent extension JAR (separate from platform extension).
6. Split production diagnostic event from test-specific stderr adapter.
7. Use `ServiceLoader`-based diagnostic listener SPI (new extension point).
8. Use javaagent extension test fixture JAR containing only `runIfEnabled` logic.
9. Keep current design with better documentation as baseline.
10. Emit F3 markers through OTel `ConfigProperties`-gated customizer hook instead of factory import.
11. Relocate F1 variants to `ClassLoaderVisibilitySpikeMain` only; remove factory call entirely if F3 verifiable elsewhere.
12. Property-gated bytecode-free adapter in test classpath loaded via `otel.javaagent.extensions` second JAR.

---

## 14. Candidate Variant Requirements For Perplexity

Каждый из 6 будущих вариантов должен явно ответить на:

1. **Production code cleanliness** — остаётся ли import `factory.spike` в factory?
2. **E2E fidelity** — сохраняются ли F1 (`nativeVisible=false`) и F3 (`customRules=1`, `PLATFORM_RULES_EXTENSIONS`)?
3. **OTel Java Agent classloader realism** — выполняется ли проверка в `ExtensionClassLoader`?
4. **Runtime overhead when disabled** — сохраняется ли O(1) property check/no-op?
5. **Accidental enablement risk** — кто может включить probe в prod?
6. **Architecture fitness impact** — нужны ли новые ArchUnit carve-outs?
7. **Implementation cost** — файлы/модули/Gradle changes.
8. **Test stability** — зависимость от stderr parsing vs slf4j/log capture.
9. **Compatibility with existing stderr markers** — break vs alias vs versioned prefix.
10. **Ability to remove "spike" wording from main code** — достижимо ли полностью?
11. **No Spring dependency in otel-extension** — соблюдение?
12. **No weakening of custom rule loading coverage** — `ExtensionRuleLoader` path still validated?
13. **Agent packaging** — probe в `agentExtensionJar` или отдельно?
14. **Documentation/ADR update burden.**
15. **Migration plan DEFER closure** — закрывает ли PR-3 debt item?

---

## 15. Rename / Move Impact Surface

| Affected Area | Expected Change | Risk |
|---------------|-----------------|------|
| `PlatformSpanProcessorFactory` | remove/replace import and call | breaking E2E F3 if marker path lost |
| `ClassLoaderVisibilitySpikeProbe` | rename/move/package | agent JAR contents, E2E imports |
| `ClassLoaderVisibilitySpikeE2ETest` | update constants/prefix/parser | test breakage |
| `ClassLoaderVisibilitySpikeMain` | update import | E2E launcher failure |
| stderr marker parsing | prefix/property change | Gentoo script + captured outputs in `build/spike-gentoo-*` |
| `docs/decisions/ADR-classloader-visibility-spike-finding.md` | sync terminology | doc drift |
| migration inventory rows | status DEFER → migrated | planning |
| `SafeBoundaryArchTest` | scope comment / rule inclusion | ArchUnit failure if probe uses stderr in wrong package |
| package names `factory.spike` | rename | all imports |
| system property `platform.tracing.spike.classloader.visibility` | rename | E2E, scripts, docs |
| `platform-tracing-e2e-tests/build.gradle` | classpath/JAR layout if probe moves | `-PrunE2e` wiring |
| `scripts/run-classloader-spike-gentoo.ps1` | JVM args / assertions | manual Linux gate |
| `agentExtensionJar` contents | probe location | agent smoke tests indirect |
| `ExtensionRuleLoader` | ideally unchanged | regression on custom rules |
| `StartupDiagnostics` | potential consolidation target | conflation of prod vs test signals |

---

## 16. Questions For Perplexity Architecture Pass

1. Как E2E может проверять javaagent classloader visibility без import spike-кода в production factories?
2. Допустимы ли gated diagnostic probes в `src/main`, если они не названы «spike»?
3. Следует ли переименовать `spike` → `diagnostics`, оставив класс в main?
4. Можно ли заменить stderr markers на `StartupDiagnostics` без потери machine-parseable E2E?
5. Может ли e2e-only javaagent extension JAR предоставить probe для F1, пока F3 проверяется иначе?
6. Как OpenTelemetry Java Agent `ExtensionClassLoader` влияет на размещение test-only кода?
7. Какие safe patterns существуют для test-only probes в Java agent extensions?
8. Должна ли активация probe использовать system property, `ConfigProperties`, или только test fixture?
9. Как architecture fitness rules должны трактовать gated diagnostics vs spikes?
10. Какой lowest-risk migration path удаляет `factory.spike` import из factory?
11. Нужна ли обратная совместимость prefix `SPIKE_CLASSLOADER:`?
12. Можно ли разделить F1 (ServiceLoader) и F3 (customRules count) на разные механизмы?
13. Достаточно ли `CustomRuleSmokeE2ETest` для F3 без factory stderr marker?
14. Требуется ли probe в factory path, если agent init всегда предшествует `main`?
15. Как избежать hardcoded `custom-e2e-rule` в main при сохранении F1?
16. Приемлем ли отдельный «diagnostics» модуль в agent JAR без слова spike?
17. Как оценить риск accidental enablement при публичном property name?
18. Нужен ли probe в production artifact после принятия ADR Variant B?
19. Можно ли использовать OTel extension SPI для регистрации optional diagnostic listener?
20. Как migration plan DEFER item должен закрываться без ослабления gates?
21. Влияет ли перенос probe на `verifyExtensionDeps` / `agentExtensionJar` tasks?
22. Следует ли синхронизировать ADR (ссылка на `appendSpiRulesFromExtensions`) с текущим `ExtensionRuleLoader`?
23. Как Gentoo cross-platform script вписывается в выбранный вариант?
24. Допустим ли `System.err` для diagnostics при ArchUnit `ACCESS_STANDARD_STREAMS` carve-out?
25. Можно ли тестировать classloader boundary только через log capture slf4j вместо stderr?
26. Как варианты соотносятся с `MapWireRoundTripE2ETest` и cross-CL JMX concerns?
27. Нужен ли property gate + CI-only enablement вместо universal `-D...=true`?
28. Какова стоимость поддержки dual contracts (`emitExtensionLoadResultIfEnabled` + `runIfEnabled`)?
29. Можно ли codegen stderr adapter из test module без production import?
30. Какой вариант минимизирует architect smell при сохранении 100% текущего E2E assert набора?

---

## 17. Evidence Appendix

### `ClassLoaderVisibilitySpikeProbe` usages (source, excluding build/)

```text
platform-tracing-otel-extension/src/main/java/.../factory/PlatformSpanProcessorFactory.java:23  import
platform-tracing-otel-extension/src/main/java/.../factory/PlatformSpanProcessorFactory.java:163 emitExtensionLoadResultIfEnabled(...)
platform-tracing-otel-extension/src/main/java/.../factory/spike/ClassLoaderVisibilitySpikeProbe.java  definition
platform-tracing-e2e-tests/src/test/java/.../ClassLoaderVisibilitySpikeE2ETest.java  import, SPIKE_PROPERTY, assertions, parser
platform-tracing-e2e-tests/src/test/java/.../ClassLoaderVisibilitySpikeMain.java  runIfEnabled()
docs/decisions/ADR-classloader-visibility-spike-finding.md
docs/architecture/platform-tracing-current-codebase-inventory.md
docs/architecture/platform-tracing-preservation-first-migration-plan.md
docs/architecture/Migration Plan/Migration Plan.md
```

### Spike property usages

```text
ClassLoaderVisibilitySpikeProbe.ENABLE_PROPERTY = "platform.tracing.spike.classloader.visibility"
Guard: "true".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY))
E2E: -Dplatform.tracing.spike.classloader.visibility=true
scripts/run-classloader-spike-gentoo.ps1: same property
docs/decisions/ADR-classloader-visibility-spike-finding.md
build/spike-gentoo-bundle/run-spike.sh (generated artifact)
```

### `SPIKE_CLASSLOADER` marker usages

```text
ClassLoaderVisibilitySpikeProbe.LINE_PREFIX = "SPIKE_CLASSLOADER:"
emit() → System.err.println(LINE_PREFIX + payload)
ClassLoaderVisibilitySpikeE2ETest: parseSpikeVariants, contains checks
scripts/run-classloader-spike-gentoo.ps1: BEGIN, targetFound
build/spike-gentoo-output.txt, build/spike-gentoo-bundle/gentoo-spike-output.txt (captured runs)
```

### `emitExtensionLoadResultIfEnabled` usages

```text
ClassLoaderVisibilitySpikeProbe.java:34  definition
PlatformSpanProcessorFactory.java:163  sole caller
```

### E2E test usages

```text
ClassLoaderVisibilitySpikeE2ETest.java — sole JUnit class
ClassLoaderVisibilitySpikeMain.java — child JVM entrypoint
platform-tracing-e2e-tests/build.gradle — system properties, -PrunE2e gate
pr-0-baseline-checklist.md — run command
```

### Docs / guardrail mentions

```text
ADR-classloader-visibility-spike-finding.md (primary)
platform-tracing-current-codebase-inventory.md
platform-tracing-preservation-first-migration-plan.md
Migration Plan/*.md
pr-0-baseline-checklist.md
phase-15-autoconfigure-extension-spi-plan.md (cross-ref)
otel-compatibility-matrix.md (cross-ref)
ADR-platform-tracing-clean-core-hybrid.md (cross-ref)
CustomRuleSmokeE2ETest.java (ADR comment, not direct probe)
ExtensionRuleLoader.java (ADR reference in Javadoc)
SafeBoundaryArchTest.java (factory.spike exclusion comment)
```

### Production references summary

```text
Count: 1 file, 1 import, 1 method call
File: PlatformSpanProcessorFactory.java
Package imported: space.br1440.platform.tracing.otel.extension.factory.spike
```

---

## 18. Non-Findings / Unknowns

### NOT FOUND IN REPOSITORY

- `platform-tracing-e2e-tests/src/main/java`
- `package-info.java` в `factory/spike`
- Environment variable activation для probe
- `ConfigProperties` activation для probe
- Unit/integration тесты probe в `platform-tracing-otel-extension/src/test/java`
- Ссылки на `ClassLoaderVisibilitySpike*` в `platform-tracing-test`, `platform-tracing-spring-boot-autoconfigure`
- Dedicated ArchRule «spike packages forbidden in production» (кроме javadoc carve-out)
- Второй production call-site probe
- Дополнительные классы в `factory/spike` кроме `ClassLoaderVisibilitySpikeProbe`
- Кэширование activation flag

### Explicit unknowns (требуют architect/product decision)

- Считаются ли diagnostic probes acceptable в main, если не названы «spike»?
- Требуется ли обратная совместимость stderr markers `SPIKE_CLASSLOADER:*`?
- Нужна ли classloader E2E coverage после закрытия ADR Variant B в production?
- Считается ли classloader investigation полностью завершённым или probe — permanent gate?
- Достаточно ли slf4j `StartupDiagnostics` как long-term observability вместо stderr?
- Нужен ли factory-path marker после стабилизации `ExtensionRuleLoader`?

---

## 19. Final Status

```text
Inventory status: COMPLETED
No refactoring performed.
Ready for Perplexity 6-variant architecture pass to remove spike wording from production code while preserving E2E coverage.
```

---

## 20. Post-Migration Status (2026-06-17)

Реализован вариант `TEST_ONLY_PROBE` + `DELETE_OLD_PROPERTY_AND_MARKERS`.

| Элемент | Статус |
|---------|--------|
| `ClassLoaderVisibilitySpikeProbe.java` | DELETED |
| `factory.spike` package | DELETED |
| `PlatformSpanProcessorFactory` import/call | REMOVED |
| `platform.tracing.spike.classloader.visibility` | REMOVED from active code |
| `SPIKE_CLASSLOADER:` marker | REMOVED from active code |
| `ClassLoaderVisibilitySpikeE2ETest` | DELETED |
| `ClassLoaderVisibilitySpikeMain` | DELETED |
| `scripts/run-classloader-spike-gentoo.ps1` | DELETED |
| `ClassLoaderVisibilityTestProbe` (test-only extension JAR) | CREATED (`platform-tracing-e2e-tests/src/testExtension`) |
| `ClassLoaderVisibilityE2ETest` | CREATED — F1 classloader visibility + optional mechanism smoke |
| `ClassLoaderVisibilityE2ELauncher` | CREATED |
| F1 marker prefix | `CL_VISIBILITY:` (replaces `SPIKE_CLASSLOADER:`) |
| F1 proof | `ClassLoaderVisibilityE2ETest` via test-only extension JAR |
| Mechanism smoke markers | `mechanismCustomRules`, `mechanismLoadingMode` (probe-side URLClassLoader; not production F3) |
| F3 production proof | `CustomRuleSmokeE2ETest` — ExtensionRuleLoader + span masking in Jaeger |
