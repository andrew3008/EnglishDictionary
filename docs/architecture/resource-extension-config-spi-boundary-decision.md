# Resource ExtensionConfig SPI Boundary Decision

> Файл: `docs/architecture/resource-extension-config-spi-boundary-decision.md`
> Контекст: PR-3R — мини-спайк по миграции Resource + `platform.tracing.service.*` в архитектуру A3'.
> Дата: 2026-06-16. OTel SDK версия: `openTelemetryBomVersion=1.62.0`.

---

## 1. Executive Summary

`PlatformResourceProvider` является независимым OTel SPI `ResourceProvider`, загружаемым через `ServiceLoader`. Его no-arg конструктор вызывается OTel SDK до и независимо от цепочки `AutoConfigurationCustomizerProvider`. Без введения глобального статического состояния `ExtensionConfig` не может быть передан в провайдер через стандартный bootstrap-маршрут.

Анализ шести архитектурных опций (R0–R5) показывает, что **RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS** является единственным выбором, который корректен с точки зрения lifecycle OTel, не вводит статического состояния и не требует инвазивного рефакторинга вне scope A3'.

Правило A3' «единственный владелец `ExtensionConfig`» требует явной поправки: `ResourceProvider`, загружаемые через `ServiceLoader`, являются легальным исключением при условии соблюдения четырёх guardrails.

---

## 2. Decision

**`RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS`**

---

## 3. Context

### PR-1 (завершён)
- Characterization + parity тесты для facade-behaviour.
- Дрейф `sampling.ratio` (facade=0.1, builder=1.0) зафиксирован тестом и задокументирован.
- Decision Note: `ALIGN_TO_EXTENSION_DEFAULTS = 0.1`.

### PR-2 (завершён)
- `ExtensionConfig` переписан как тонкий facade с 11 `final`-полями domain-конфигов.
- `ExtensionConfigReader` — package-private helper для чтения `ConfigProperties`.
- Все domain config классы (`public final`, package-private constructor) не держат `ConfigProperties`.
- `ResourceExtensionConfig` создан с полями `policyVersion`, `normalizeEnvironment`, `validationMode`, `detectContainerId`.
- ArchUnit guardrail: `*ExtensionConfig` (кроме `ExtensionConfig`) не должен зависеть от `ConfigProperties`.

### PR-3 (завершён частично — PARTIAL_PR3_RESOURCE_DEFERRED)
Мигрированы: Queue, sdk.mode, Metrics, Enriching, Baggage, Classification, Watchdog, Validation startup.  
Не мигрированы: Sampling, Scrubbing, **Resource + service.***.

**Причина откладывания Resource:** `PlatformResourceProvider` является OTel SPI `ResourceProvider`, регистрируемым через `ServiceLoader`. Его конструктор вызывается SDK независимо от `PlatformAutoConfigurationCustomizer`. Чтобы передать `ExtensionConfig` в провайдер, потребовалось бы глобальное статическое состояние — что нарушает принципы тест-изоляции и является неприемлемым для консервативного refactoring-подхода.

### PR-3R (этот документ)
Мини-спайк по принятию решения. Только документ, без изменений production-кода.

---

## 4. Repository Evidence

| Факт | Источник |
|---|---|
| `SafeResourceProvider` зарегистрирован как OTel SPI | `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider` → `SafeResourceProvider` |
| No-arg конструктор: `new PlatformResourceProvider()` | `SafeResourceProvider` строка 42 |
| `ResourceAttributeResolver.resolve(ConfigProperties, ...)` — deep dependency | `ResourceAttributeResolver.java` строки 63–89 |
| `ResourceConfiguration.createEnvironmentResource(config)` — per-key omit | `ResourceAttributeResolver.java` строки 67, 102 — требует `ConfigProperties` |
| Нет `addResourceCustomizer` в `PlatformAutoConfigurationCustomizer` | grep: `addResource\|ResourceCustomizer` — 0 совпадений в `src/main/java` |
| `addResourceCustomizer` API в OTel SDK 1.62.0 существует, но не используется | grep: 0 использований |
| `service.*` константы — только в `PlatformResourceProvider` | `PlatformResourceProvider.java` строки 55–61 |
| `service.*` константы — **отсутствуют** в `ExtensionPropertyNames` | Grep по `SERVICE_NAME\|PROP_SERVICE` в `ExtensionPropertyNames.java` — 0 совпадений |
| `ResourceExtensionConfig` создан (PR-2), но не потребляется production-кодом resource-слоя | grep `ResourceExtensionConfig` в `src/main/java/...resource/` — 0 совпадений |
| OTel BOM версия `1.62.0` | `gradle.properties` строка 10 |
| `PlatformTracingDefaultsProvider` использует `PlatformResourceProvider.PROP_SERVICE_NAME` | `PlatformTracingDefaultsProvider.java` строки 46–49 |
| `ExtensionPropertyNames.RESOURCE_VALIDATION_MODE` читается в `SafeResourceProvider` | `SafeResourceProvider.java` строка 81 |
| `ExtensionPropertyNames.RESOURCE_NORMALIZE_ENVIRONMENT` читается в `ResourceAttributeResolver` | `ResourceAttributeResolver.java` строка 69 |
| `ExtensionPropertyNames.RESOURCE_POLICY_VERSION` читается в `ResourceAttributeResolver` | `ResourceAttributeResolver.java` строки 104, 170 |

---

## 5. Resource Lifecycle Analysis

### Как OTel SDK создаёт и вызывает ResourceProvider

```
1. OTel SDK собирает все AutoConfigurationCustomizerProvider через ServiceLoader.
2. Вызывает customize() на каждом → регистрирует callbacks (addPropertiesCustomizer и др.).
3. Финализирует ConfigProperties (запускает все addPropertiesCustomizer).
4. Собирает все ResourceProvider через ServiceLoader (независимый ServiceLoader pass).
5. Вызывает shouldApply(config, existing) и createResource(config) на каждом ResourceProvider
   (по возрастанию order()).
6. Продолжает: SamplerProvider, TracerProvider, SpanProcessors и т.д.
```

Ключевое: шаг 4 — ServiceLoader для `ResourceProvider` — происходит **после** шага 3 (финализация ConfigProperties), поэтому `addPropertiesCustomizer` технически уже завершился. Это означает, что статический bridge (R1) был бы заполнен вовремя.

Однако ServiceLoader в шаге 4 создаёт экземпляры провайдеров через **no-arg конструктор** — без какой-либо возможности инжекции зависимостей. Единственный способ «прокинуть» что-либо из шага 3 в шаг 4 — это глобальное статическое состояние.

### Доп. сложность: ConditionalResourceProvider

`SafeResourceProvider` реализует `ConditionalResourceProvider` (пакет `io.opentelemetry.sdk.autoconfigure.spi.internal` — помечен internal). `shouldApply(ConfigProperties, Resource existing)` принимает **уже накопленный** `Resource` — это невозможно реализовать через `addResourceCustomizer(BiFunction<Resource, ConfigProperties, Resource>)` без потери логики per-key omit.

---

## 6. Current Resource Property Map

| Property | Текущий владелец константы | Читает | Default | Runtime mutable? | В `ResourceExtensionConfig`? |
|---|---|---|---|---|---|
| `platform.tracing.resource.policy-version` | `ExtensionPropertyNames.RESOURCE_POLICY_VERSION` | `ResourceAttributeResolver` | `"2026.06.08"` | Нет | ✓ (`policyVersion()`) |
| `platform.tracing.resource.normalize-environment` | `ExtensionPropertyNames.RESOURCE_NORMALIZE_ENVIRONMENT` | `ResourceAttributeResolver` | `true` | Нет | ✓ (`normalizeEnvironment()`) |
| `platform.tracing.resource.validation-mode` | `ExtensionPropertyNames.RESOURCE_VALIDATION_MODE` | `SafeResourceProvider`, `PlatformResourceProvider` | `"LENIENT"` | Нет | ✓ (`validationMode()`) |
| `platform.tracing.resource.detect-container-id` | `ExtensionPropertyNames.RESOURCE_DETECT_CONTAINER_ID` | `PlatformResourceProvider` | `false` | Нет | ✓ (`detectContainerId()`) |
| `platform.tracing.service.name` | `PlatformResourceProvider.PROP_SERVICE_NAME` | `ResourceAttributeResolver` | (из build-info/manifest) | Нет | ✗ |
| `platform.tracing.service.version` | `PlatformResourceProvider.PROP_SERVICE_VERSION` | `ResourceAttributeResolver` | (из build-info/manifest) | Нет | ✗ |
| `platform.tracing.service.environment` | `PlatformResourceProvider.PROP_ENVIRONMENT` | `ResourceAttributeResolver` | `""` (EnvironmentNormalizer) | Нет | ✗ |
| `platform.tracing.service.c-group` | `PlatformResourceProvider.PROP_C_GROUP` | `ResourceAttributeResolver` | отсутствует | Нет | ✗ |
| `platform.tracing.service.id` | `PlatformResourceProvider.PROP_ID` | `ResourceAttributeResolver` | отсутствует | Нет | ✗ |
| `platform.tracing.service.host` | `PlatformResourceProvider.PROP_HOST` | `PlatformResourceProvider.createResource` | (из HostNameResolver) | Нет | ✗ |
| `platform.tracing.service.container-id` | `PlatformResourceProvider.PROP_CONTAINER_ID` | `PlatformResourceProvider.resolveContainerId` | отсутствует | Нет | ✗ |

**Наблюдение:** `resource.*` константы уже в `ExtensionPropertyNames` — они читаются через shared constants, что соответствует guardrail R0. `service.*` константы определены в `PlatformResourceProvider` — это drift risk, который должен быть адресован guardrail'ом на миграцию или зафиксирован как исключение.

---

## 7. Option Analysis

### R0 — Keep ResourceProvider as explicit OTel SPI exception

`PlatformResourceProvider` продолжает читать `ConfigProperties` напрямую. Правило A3' явно поправлено: `ResourceProvider`, загружаемые через ServiceLoader, являются легальным исключением. Добавляются guardrails (parity тесты, ArchUnit, shared constants).

| Dimension | Assessment |
|---|---|
| Implementation complexity | Минимальная — никакого production-кода не меняется |
| Lifecycle correctness | 10/10 — SPI lifecycle не нарушается |
| OTel ServiceLoader compatibility | Полная |
| A3' single bootstrap owner | Явное задокументированное исключение |
| Static/global state risk | Отсутствует |
| Config drift risk | Низкий — guardrails фиксируют parity через тесты |
| Testability | Высокая — существующие тесты продолжают работать |
| Rollback ease | Тривиальный (нечего откатывать) |
| Spring/JMX/runtime safety | Полная — ничего не меняется |
| Property-key safety | Полная — ключи не переименовываются |
| Resource behavior unchanged | ✓ |

### R1 — Static ExtensionConfig bridge

`PlatformAutoConfigurationCustomizer` сохраняет `ExtensionConfig` в `static AtomicReference`. `SafeResourceProvider` / `PlatformResourceProvider` читают из неё.

| Dimension | Assessment |
|---|---|
| Implementation complexity | Средняя — добавляется static holder класс, null-check в ResourceProvider |
| Lifecycle correctness | 6/10 — lifecycle order случаен при параллельной инициализации |
| OTel ServiceLoader compatibility | Работает при условии single-classloader; хрупко |
| A3' single bootstrap owner | Технически соответствует, но через скрытый side-channel |
| Static/global state risk | **Критический** — тесты загрязняют друг друга, `AtomicReference.get()` возвращает config предыдущего теста |
| Config drift risk | Низкий, если bridge заполнен; высокий при null (NPE или stale) |
| Testability | Низкая — статическое состояние требует ручного сброса в @AfterEach |
| Rollback ease | Средняя |
| Spring/JMX/runtime safety | Нет изменений |
| Property-key safety | Полная |
| Resource behavior unchanged | Только при корректном заполнении bridge |

**Вердикт: неприемлемо.** Статическое глобальное состояние в codebase с активным test-suite — источник трудноотлаживаемых test-pollution ошибок.

### R2 — ResourceProvider constructs local ResourceExtensionConfig from ConfigProperties

`PlatformResourceProvider` или `ResourceAttributeResolver` конструирует `ResourceExtensionConfig` напрямую из `ConfigProperties`.

| Dimension | Assessment |
|---|---|
| Implementation complexity | Средняя — требует изменения package-private constraints |
| Lifecycle correctness | 8/10 — корректно, ConfigProperties всё ещё приходит от OTel |
| OTel ServiceLoader compatibility | Полная |
| A3' single bootstrap owner | **Нарушение** — domain config конструируется вне bootstrap owner |
| Static/global state risk | Отсутствует |
| Config drift risk | Зависит от реализации |
| Testability | Средняя |
| Rollback ease | Средняя |
| Spring/JMX/runtime safety | Полная |
| Property-key safety | Полная |
| Resource behavior unchanged | ✓ при корректной реализации |

**Техническая блокировка:** `ResourceExtensionConfig` constructor package-private, `ExtensionConfigReader` package-private — оба в пакете `configuration`. Из пакета `resource` их вызвать нельзя без добавления публичного factory method. Это изменяет encapsulation PR-2 ради одного нестандартного consumer'а.

### R3 — Customizer-based resource registration (addResourceCustomizer)

OTel SDK 1.62.0 содержит `AutoConfigurationCustomizer.addResourceCustomizer(BiFunction<Resource, ConfigProperties, Resource>)`. `PlatformAutoConfigurationCustomizer` регистрирует resource customizer вместо SPI.

| Dimension | Assessment |
|---|---|
| Implementation complexity | **Высокая** — полная замена SPI, потеря ConditionalResourceProvider/shouldApply, переписывание per-key omit логики |
| Lifecycle correctness | 9/10 — customizer chain lifecycle |
| OTel ServiceLoader compatibility | Не использует ServiceLoader — правильно, но breaking change |
| A3' single bootstrap owner | ✓ — ExtensionConfig доступен через closure |
| Static/global state risk | Отсутствует |
| Config drift risk | Низкий |
| Testability | Высокая |
| Rollback ease | Сложная — SPI надо удалять из META-INF/services |
| Spring/JMX/runtime safety | Полная |
| Property-key safety | Полная |
| Resource behavior unchanged | Требует тщательной reimplementation shouldApply |

**Ключевое препятствие:** `ConditionalResourceProvider` является internal SPI OTel. При переходе на `addResourceCustomizer` теряется логика условного применения (`shouldApply(config, existing)`). Это не просто рефакторинг — это redesign resource provisioning.

**Заключение:** Технически обоснованный future path, но вне scope A3'. Требует отдельного PR с фокусом на redesign ResourceProvider.

### R4 — Split Resource concerns

Сохранить `PlatformResourceProvider` для service identity keys (`service.*`), мигрировать policy config (`policyVersion`, `normalizeEnvironment`, `validationMode`, `detectContainerId`) в `ResourceExtensionConfig` через какой-либо механизм.

| Dimension | Assessment |
|---|---|
| Implementation complexity | Высокая — `ResourceAttributeResolver.resolve()` читает оба типа через один `ConfigProperties` |
| Lifecycle correctness | 7/10 |
| OTel ServiceLoader compatibility | Частичная |
| A3' single bootstrap owner | Частичное соответствие |
| Static/global state risk | Зависит от реализации split |
| Config drift risk | Повышенный — split boundary неочевиден |
| Testability | Средняя |
| Rollback ease | Сложная |
| Spring/JMX/runtime safety | Полная |
| Property-key safety | Полная |
| Resource behavior unchanged | Требует тщательной reimplementation |

**Вердикт: архитектурно несвязный.** policy config и service identity вычисляются в одном методе (`ResourceAttributeResolver.resolve`) с одним `ConfigProperties`. Разделение потребует передачи двух источников данных в один resolver — это усложнение без ясного бенефита.

### R5 — Defer Resource migration entirely

Resource остаётся вне scope A3'. Никаких изменений, никаких guardrails.

| Dimension | Assessment |
|---|---|
| Implementation complexity | Нулевая |
| Lifecycle correctness | 10/10 |
| OTel ServiceLoader compatibility | Полная |
| A3' single bootstrap owner | N/A — Resource явно вне scope |
| Static/global state risk | Отсутствует |
| Config drift risk | **Повышенный** — без parity тестов drift может накапливаться |
| Testability | Существующие тесты достаточны |
| Rollback ease | Тривиальная |
| Spring/JMX/runtime safety | Полная |
| Property-key safety | Полная |
| Resource behavior unchanged | ✓ |

**Вердикт:** R5 является деградированной версией R0. R0 строго лучше: добавляет guardrails к тем же изменениям (= ничего). Выбор между R5 и R0 — это выбор между «молча откладываем» и «явно документируем как исключение с защитами».

---

## 8. Scoring Matrix

*Шкала 1–10, выше = лучше.*

| Option | Lifecycle correctness | A3' fit | Low risk | No static state | Drift safety | Testability | Simplicity | **Total** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| R0 — SPI exception | **10** | 7 | **10** | **10** | 8 | 9 | **10** | **64** |
| R1 — Static bridge | 6 | 9 | 4 | 1 | 7 | 5 | 5 | 37 |
| R2 — Local config | 7 | 6 | 7 | 9 | 7 | 7 | 4 | 47 |
| R3 — Customizer | 9 | 9 | 5 | 10 | 9 | 8 | 3 | 53 |
| R4 — Split | 7 | 6 | 7 | 9 | 7 | 6 | 4 | 46 |
| R5 — Defer | 10 | 5 | 10 | 10 | 6 | 8 | 10 | 59 |

R0 занимает первое место. R3 на втором месте, но требует объёма работы, выходящего за рамки A3' при значительно более низкой simplicity-оценке.

---

## 9. Final Recommendation

### `RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS`

**Ратionale:**

1. **OTel ServiceLoader lifecycle** — фундаментальное ограничение, не дефект refactoring'а. `SafeResourceProvider` создаётся no-arg конструктором вне customizer chain. Это не недосмотр — это архитектурная граница OTel SDK.

2. **Статическое состояние (R1) неприемлемо** — в codebase с интеграционными тестами, использующими `AutoConfiguredOpenTelemetrySdk.builder().build()` в одном JVM процессе, `AtomicReference<ExtensionConfig>` будет удерживать state предыдущего теста. Это источник нестабильных тестов.

3. **Package-private constraints (R2)** — нарушение encapsulation PR-2 ради одного нестандартного consumer'а.

4. **R3 (addResourceCustomizer) — правильный long-term path**, но требует полного redesign `PlatformResourceProvider`: потеря `ConditionalResourceProvider`/`shouldApply`, reimplementation per-key omit semantics. Это отдельный PR с другим scope.

5. **R0 + явные guardrails** даёт все практические бенефиты A3' для Resource: parity тесты зафиксируют поведение, shared constants исключат key drift, ArchUnit предотвратит регрессию зависимостей.

### Поправка к правилу A3'

Правило «единственный владелец `new ExtensionConfig`» переформулируется:

> `ExtensionConfig` является единственным авторитетным startup-источником конфигурации для **customizer/factory chain** (`PlatformAutoConfigurationCustomizer` + все factories/builders, которые он создаёт). OTel SPI провайдеры, загружаемые независимо через ServiceLoader (`ResourceProvider`, `ConfigurableSamplerProvider`, `ConfigurablePropagatorProvider`), **легально читают `ConfigProperties` напрямую**, поскольку находятся за пределами customizer chain по дизайну OTel SDK. Такие провайдеры обязаны: (1) использовать shared constants из `ExtensionPropertyNames`/`ExtensionDefaults`, (2) не держать `ConfigProperties` как instance field, (3) быть покрытыми parity тестами.

---

## 10. Impact on A3' Plan

A3' **не требует смены стратегии**. Поправка к правилу single-bootstrap-owner делает позицию по Resource **эксплицитной**, а не молчаливой. Остальные шесть PR-ов плана не затрагиваются:

- PR-4 (Scrubbing): продолжается как запланировано — Scrubbing полностью внутри customizer chain.
- PR-5 (Sampling/`ALIGN_TO_EXTENSION_DEFAULTS`): без изменений.
- PR-6 (документация + formalized guardrails): должен **включить** formalization этой поправки.

`ResourceExtensionConfig` (PR-2) остаётся в кодовой базе. Он корректно отражает resource policy properties. Если в будущем будет принято решение мигрировать Resource через R3-подход, `ResourceExtensionConfig` уже готов принять значения.

---

## 11. Required Guardrails

При R0 следующие guardrails обязательны для предотвращения дрейфа:

### G-R1: Shared constants
`PlatformResourceProvider.PROP_SERVICE_*` константы должны оставаться единственными определениями строк `platform.tracing.service.*`. Если они когда-либо дублируются в другом классе, это нарушение.

```text
Файлы, которые могут определять service.* константы:
  ТОЛЬКО PlatformResourceProvider
  
Файлы, которые могут использовать эти константы:
  ResourceAttributeResolver (через PlatformResourceProvider.PROP_*)
  PlatformTracingDefaultsProvider (уже делает это правильно)
```

### G-R2: No ExtensionDefaults direct reads in resource package
Пакет `resource` не должен импортировать `ExtensionDefaults` для получения дефолтных значений — только через `ExtensionPropertyNames`-константы или через публичные дефолты `ResourceExtensionConfig`.

```text
ArchUnit check (добавить в будущем PR-6):
  noClasses()
    .that().resideInPackage("...resource")
    .should().dependOnClassesThat().haveFullyQualifiedName(ExtensionDefaults.class)
    .because("resource package reads defaults via ExtensionPropertyNames only")
```

*Примечание: сейчас `ResourceAttributeResolver` использует `ExtensionDefaults.DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT` и `DEFAULT_RESOURCE_POLICY_VERSION` — это нарушение G-R2. Текущее состояние нужно исправить в рамках guardrail-работы (вынести дефолты в `ExtensionPropertyNames` как дефолтные String константы, или сделать их доступными через `ResourceExtensionConfig.normalizeEnvironment()` статического метода с дефолтом). До исправления — зафиксировать в тесте.*

### G-R3: Parity test coverage
Должен существовать тест, доказывающий что:
- defaults в `ResourceExtensionConfig` (из PR-2) совпадают с defaults в `ResourceAttributeResolver`/`PlatformResourceProvider`
- `platform.tracing.resource.policy-version` default одинаков в обоих путях
- `platform.tracing.resource.normalize-environment` default одинаков в обоих путях

### G-R4: No-arg constructor documentation
Javadoc на `SafeResourceProvider()` и `PlatformResourceProvider()` должен явно указывать, что конструктор вызывается OTel ServiceLoader и что `ExtensionConfig` здесь недоступен по дизайну.

---

## 12. Required Tests for Future Resource PR

Если в будущем будет решено реализовать R3 (addResourceCustomizer) или любой другой подход, следующие тесты обязательны:

```text
1. ResourceExtensionConfig vs ResourceAttributeResolver parity test:
   - Default policyVersion совпадает
   - Default normalizeEnvironment совпадает
   - Default validationMode совпадает
   - Default detectContainerId совпадает
   
2. service.* key preservation test:
   - Все 7 service.* ключей читаются с корректными строковыми значениями
   - Тест на точное равенство строки "platform.tracing.service.name" etc.
   
3. Per-key omit semantics:
   - OTEL_SERVICE_NAME → platform не перетирает
   - OTEL_RESOURCE_ATTRIBUTES=... → platform не перетирает
   
4. shouldApply behavior:
   - Если existing.getAttribute(POLICY_VERSION) != null → shouldApply = false (при прочих)
   
5. Backward compatibility:
   - Миграция с ResourceProvider SPI на addResourceCustomizer не меняет Resource attributes
   - ResourceMergeIntegrationTest зелёный
```

---

## 13. Stop Conditions for Future Resource Work

Остановить работу по Resource миграции (R3 или любой другой) при обнаружении:

```text
STOP-R1: Изменение строк ключей platform.tracing.service.* или platform.tracing.resource.*
STOP-R2: ConditionalResourceProvider shouldApply не может быть корректно reimplemented
         через addResourceCustomizer BiFunction
STOP-R3: OTel SDK API internal изменения (ConditionalResourceProvider смена пакета/сигнатуры)
STOP-R4: ResourceAttributeResolver.resolve() требует доступа к runtime state
STOP-R5: Любой из service.* провайдеров (HostNameResolver, ProcfsContainerIdDetector)
         требует JMX/runtime доступа
STOP-R6: Spring зависимость появляется в otel-extension
STOP-R7: ResourceMergeIntegrationTest или PlatformResourceProviderTest не проходят
```

---

## 14. Updated PR Plan Recommendation

**Продолжить с PR-4 Scrubbing only.**

```text
Рекомендуемая последовательность PR:

PR-4:  Scrubbing startup migration (PlatformSpanProcessorFactory.collectScrubbingRules → ScrubbingExtensionConfig)
PR-5:  Sampling adoption + ALIGN_TO_EXTENSION_DEFAULTS (PlatformSamplerBuilder → SamplingExtensionConfig)  
PR-6:  Документация startup/runtime boundary + formalized guardrails + поправка A3' + G-R1..G-R4
PR-R3: [FUTURE, вне A3'] ResourceProvider redesign через addResourceCustomizer
       — только если dedicated need появится; не блокирует A3' completion
```

Resource остаётся **постоянным SPI-исключением** для A3'. A3' считается завершённым без Resource migration. Это не технический долг — это корректное документирование архитектурной границы OTel SDK.

---

## 15. One-Paragraph Message for Architects

`PlatformResourceProvider` остаётся на прямом чтении `ConfigProperties` не потому, что мы не смогли его мигрировать, а потому что OTel SDK архитектурно разделяет две независимые точки интеграции: `AutoConfigurationCustomizerProvider` (customizer chain, куда мы прокидываем `ExtensionConfig`) и `ResourceProvider` (ServiceLoader SPI, независимый no-arg lifecycle). Введение статического bridge было бы технически возможным, но принесло бы test pollution в codebase с интеграционными тестами — это неприемлемый трadeoff для pre-production codebase, где тестовая надёжность критична. Выбранный R0 делает это исключение **явным** через поправку к правилу A3': customizer/factory chain управляется `ExtensionConfig`, SPI providers — через `ConfigProperties` + shared constants + parity тесты. Если в будущем понадобится полная миграция, правильным path является R3 через `addResourceCustomizer` — но это deserves отдельный PR с полным redesign `PlatformResourceProvider`, не вспомогательный шаг внутри A3'.

---

## 16. Cursor Agent Prompt Outline for the Next Implementation PR

**Следующий PR: PR-4 Scrubbing (не Resource).**

Resource migration корректно оставлена как задокументированное SPI-исключение с guardrails в PR-6. Реализовывать Resource migration сейчас не нужно.

**PR-4 outline:**
```text
Цель: мигрировать scrubbing-путь в PlatformSpanProcessorFactory на ScrubbingExtensionConfig.

Scope:
- PlatformSpanProcessorFactory.collectScrubbingRules(ConfigProperties) →
  collectScrubbingRules(ScrubbingExtensionConfig, ConfigProperties)
  (ConfigProperties остаётся для ExtensionRuleLoader/ServiceLoader путей до PR-6)
- ScrubbingExtensionConfig потребляется для: enabled, builtInRules, hmacKey, missingKeyPolicy,
  hashKeyId, rulesConfig, rulesExtensions, rulesValidationMode
- PlatformAutoConfigurationCustomizer передаёт extConfig.scrubbing() в фабрику
- Тесты: parity test facade vs scrubbing factory behavior
- Не трогать: Sampling, JMX, RuntimeConfigApplier, Resource

Запрещено:
- Изменять ValidationRuntimeConfig, SamplingRuntimeConfig, ScrubbingRuntimeConfig
- Добавлять Spring в otel-extension
- Менять строки ключей свойств
```
