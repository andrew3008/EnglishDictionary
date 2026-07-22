# ExtensionConfig Final Architecture

> **Статус:** COMPLETED — A3' code refactoring завершён (PR-1 → PR-5). Architecture closure завершён (PR-6).  
> **Решение:** `APPROVE_A3_PREPROD_CLEAN_TARGET_COMPLETED`  
> **Дата:** 2026-06-16  
> **Пакет:** `space.br1440.platform.tracing.otel.javaagent.configuration`

---

## 1. Executive Summary

`ExtensionConfig` был преобразован из пустого неиспользуемого facade в авторитетный единственный startup-источник конфигурации agent-side extension. Все production factory-классы теперь получают типизированные immutable domain config объекты через конструктор или метод, а не читают `ConfigProperties` / `ExtensionPropertyNames` / `ExtensionDefaults` напрямую. Поведение runtime (JMX, `SamplerStateHolder`, `RuntimeConfigApplier`) не затронуто.

Единственное намеренное поведенческое изменение: `ALIGN_TO_EXTENSION_DEFAULTS` — дефолт `sampling.ratio` при отсутствии или пустом значении изменён с 1.0 (hardcoded implicit fallback в `PlatformSamplerBuilder`) на 0.1 (`ExtensionDefaults.DEFAULT_SAMPLING_RATIO`), зафиксированный в `SamplingExtensionConfig`.

---

## 2. Final Decision

**Decision:** `APPROVE_A3_PREPROD_CLEAN_TARGET_COMPLETED`

Рефакторинг выполнен в pre-production окне. `ExtensionConfig` стал thin facade, строящим immutable per-domain config объекты ровно один раз при bootstrap. Все фабрики мигрированы на domain config. OTel SPI исключения задокументированы и огорожены тестами.

---

## 3. What Changed

| PR | Цель | Ключевые изменения |
|---|---|---|
| PR-1 | Characterization + drift lock | Characterization-тесты parity, зафиксирован дрейф `sampling.ratio`, создан `sampling-ratio-drift-decision-note.md` |
| PR-2 | ExtensionConfig thin facade | `ExtensionConfig` переписан: читает `ConfigProperties` один раз через `ExtensionConfigReader`, строит 11 immutable domain config классов. Удалены nested классы. ArchUnit guardrail G1. |
| PR-3 | Non-sampling/non-scrubbing adoption | `Queue`, `Sdk`, `Metrics`, `Enriching`, `Baggage`, `Classification`, `Watchdog`, `Validation` startup мигрированы. `PlatformAutoConfigurationCustomizer` — единственный bootstrap owner. |
| PR-3R | Resource SPI boundary decision | `RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS`: `PlatformResourceProvider` остаётся прямым читателем `ConfigProperties`. Документ: `resource-extension-config-spi-boundary-decision.md`. |
| PR-4 | Scrubbing adoption | `PlatformSpanProcessorFactory` мигрирован на `ScrubbingExtensionConfig`. |
| PR-4B | ScrubbingRulesLoader cleanup | `ScrubbingRulesLoader.load(String, ClassLoader)` стал `public`. Последнее прямое чтение scrubbing-свойства из `ConfigProperties` в фабрике удалено. |
| PR-5 | Sampling adoption + ALIGN_TO_EXTENSION_DEFAULTS | `PlatformSamplerBuilder` мигрирован на `SamplingExtensionConfig`. Дрейф `sampling.ratio` устранён: дефолт 1.0 → 0.1. `PlatformSamplerProvider` — задокументированное OTel SPI исключение. |

---

## 4. Final Architecture Diagram

```
Startup bootstrap (OTel SDK autoconfigure lifecycle):
─────────────────────────────────────────────────────────────────────────
  ConfigProperties  (merged env/system/-D properties)
        │
        ▼  addPropertiesCustomizer (PlatformAutoConfigurationCustomizer)
  ExtensionConfig   ← единственный конструктор в bootstrap-цепочке
        │
        ├── SamplingExtensionConfig      → PlatformSamplerFactory
        │                                  PlatformSamplerBuilder
        ├── ScrubbingExtensionConfig     → PlatformSpanProcessorFactory
        ├── QueueExtensionConfig         → PlatformExportProcessorFactory
        ├── SdkExtensionConfig           → PlatformAutoConfigurationCustomizer (logging)
        ├── MetricsExtensionConfig       → PlatformSpanProcessorFactory
        ├── EnrichingExtensionConfig     → PlatformSpanProcessorFactory
        ├── BaggageExtensionConfig       → PlatformSpanProcessorFactory
        ├── ClassificationExtensionConfig→ PlatformSpanProcessorFactory
        ├── WatchdogExtensionConfig      → PlatformSpanProcessorFactory
        ├── ValidationExtensionConfig    → PlatformSpanProcessorFactory
        └── ResourceExtensionConfig      → (не использован: SPI exception ниже)

OTel SPI exceptions (ServiceLoader lifecycle, отдельные конструкторы):
─────────────────────────────────────────────────────────────────────────
  ConfigProperties → PlatformResourceProvider / SafeResourceProvider
                     (прямое чтение; RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS)

  ConfigProperties → new ExtensionConfig(config).sampling()
                  └→ PlatformSamplerProvider.createSampler()
                     (named SPI; строит минимальный ExtensionConfig локально)

Runtime (не связан с ExtensionConfig после bootstrap):
─────────────────────────────────────────────────────────────────────────
  JMX / PlatformTracingControlMBean
        │
        ▼
  SamplerStateHolder  (atomic lock-free state)
        │
        ▼
  CompositeSampler → shouldSample() hot-path
```

---

## 5. Startup vs Runtime Boundary

`ExtensionConfig` — **startup-only** объект. Он создаётся ровно один раз в `addPropertiesCustomizer` bootstrap-фазы OTel SDK autoconfigure и более не изменяется.

**Runtime mutable owner** — `SamplerStateHolder`. Он содержит атомарный снимок `SamplerState` (enabled/ratio/dropPaths/forceValues/routeRatios) и обновляется через CAS при JMX-командах. `ExtensionConfig.sampling()` seedирует начальное состояние `SamplerStateHolder` при старте — и это единственная точка соприкосновения.

| Компонент | Назначение | Мутируемость |
|---|---|---|
| `ExtensionConfig` | Startup config facade | Immutable, создаётся один раз |
| `SamplerStateHolder` | Live runtime sampling state | Атомарно мутируемый через CAS |
| `PlatformTracingControlMBean` | JMX entry point для runtime обновлений | Без изменений (PR-6) |
| `RuntimeConfigApplier` | Spring-side bridge из `TracingProperties` → JMX | Без изменений (PR-6) |

`RuntimeConfigApplier` не читает `ExtensionConfig` и не связан с ним. `SamplerStateHolder` не кэширует `SamplingExtensionConfig`. Runtime-обновления идут только через `PlatformTracingControlMBean.updateSamplingPolicy` → `SamplerStateHolder`.

---

## 6. Domain Config Ownership

| Domain | Domain config | Производственное применение | Runtime mutable? | Примечания |
|---|---|---|---|---|
| Sampling | `SamplingExtensionConfig` | `PlatformSamplerBuilder` (PR-5) | Частично: ratio/enabled/dropPaths через `SamplerStateHolder` | Seed при старте; runtime через JMX |
| Scrubbing | `ScrubbingExtensionConfig` | `PlatformSpanProcessorFactory` (PR-4, PR-4B) | Нет | `rulesConfig` передаётся в `ScrubbingRulesLoader` |
| Queue | `QueueExtensionConfig` | `PlatformExportProcessorFactory` (PR-3) | Нет | overflow-policy, concurrency |
| Sdk | `SdkExtensionConfig` | `PlatformAutoConfigurationCustomizer` (PR-3) | Нет | `sdk.mode` diagnostic log |
| Metrics | `MetricsExtensionConfig` | `PlatformSpanProcessorFactory` (PR-3) | Нет | enabled flag |
| Enriching | `EnrichingExtensionConfig` | `PlatformSpanProcessorFactory` (PR-3) | Нет | remoteServicePriority |
| Baggage | `BaggageExtensionConfig` | `PlatformSpanProcessorFactory` (PR-3) | Нет | allowlistKeys, denyPatterns |
| Classification | `ClassificationExtensionConfig` | `PlatformSpanProcessorFactory` (PR-3) | Нет | slowThreshold |
| Watchdog | `WatchdogExtensionConfig` | `PlatformSpanProcessorFactory` (PR-3) | Нет | spanTimeout |
| Validation | `ValidationExtensionConfig` | `PlatformSpanProcessorFactory` (PR-3) | Нет | strict, strictRuntimeAllowed |
| Resource | `ResourceExtensionConfig` | Не применяется в production (SPI exception) | Нет | `PlatformResourceProvider` читает `ConfigProperties` напрямую |

**Примечание по `forceRecordHeader` / `qaHeader`:** эти поля хранятся в `SamplingExtensionConfig`, но не используются `PlatformSamplerBuilder` (не нужны на уровне startup-seed). Они доступны для будущего использования.

---

## 7. OTel SPI Exceptions

Правило «единственный конструктор `ExtensionConfig` в bootstrap-цепочке» содержит два задокументированных исключения для OTel ServiceLoader SPI providers.

### 7.1. PlatformResourceProvider / SafeResourceProvider

**Решение:** `RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS`  
**Причина:** `ResourceProvider` загружается через `ServiceLoader` с no-arg constructor до и независимо от `PlatformAutoConfigurationCustomizer`. У него нет доступа к bootstrap `ExtensionConfig`.  
**Разрешено:** прямое чтение `ConfigProperties` для `service.*`, `resource.*`, `container.*` свойств.  
**Запрещено:** хранить `ConfigProperties` как поле; читать sampling/scrubbing/queue-домены.  
**Гардрейл:** `ResourceKeysNotInSpanProcessorsArchTest` защищает от инверсии (span-процессоры не пишут resource-ключи). Подробности: `resource-extension-config-spi-boundary-decision.md`.

### 7.2. PlatformSamplerProvider

**Причина:** named sampler SPI (`otel.traces.sampler=platform`) активируется через `ServiceLoader` до `addSamplerCustomizer` inline-chain. Нет доступа к bootstrap `ExtensionConfig` из `PlatformAutoConfigurationCustomizer`.  
**Разрешено:** `new ExtensionConfig(config).sampling()` в методе `createSampler(ConfigProperties)` — строит локальный минимальный `ExtensionConfig` только для получения `SamplingExtensionConfig`. Не хранит `ConfigProperties` как поле.  
**Запрещено:** хранить `ConfigProperties` как поле класса; читать domain-свойства за пределами `sampling()`; обходить idempotency-guard (inline `PlatformSamplerFactory` обнаруживает уже построенный платформенный sampler и не оборачивает повторно).  
**Тест:** `PlatformSamplerProviderTest.createSampler_builds_composite` покрывает поведение.

### 7.3. Что запрещено для всех остальных

Ни один другой production-класс **не должен** вызывать `new ExtensionConfig(config)`. Это обеспечивается:
- ArchUnit guardrail G4 (см. §11).
- Принципом: factory-классы получают domain config через конструктор или метод-параметр.

---

## 8. Sampling Ratio Decision

**Дрейф (до PR-5):**

| Компонент | Поведение при отсутствии `sampling.ratio` | Значение |
|---|---|---|
| `ExtensionConfig.sampling().ratio()` | `getDouble(SAMPLING_RATIO, DEFAULT_SAMPLING_RATIO)` | 0.1 |
| `PlatformSamplerBuilder.build()` (до PR-5) | `getString == null` → `defaultRatio = 1.0` (hardcoded) | 1.0 |

**Решение:** `ALIGN_TO_EXTENSION_DEFAULTS`

После PR-5 `PlatformSamplerBuilder` принимает `SamplingExtensionConfig` и использует `sampling.ratio()`, который defaults к `ExtensionDefaults.DEFAULT_SAMPLING_RATIO = 0.1`. Дрейф устранён.

**Поведение после PR-5:**

| Значение свойства | Поведение |
|---|---|
| Отсутствует (`null`) | 0.1 (ExtensionDefaults) |
| Пустая строка / blank | 0.1 (ExtensionDefaults, `getDouble` возвращает null) |
| `0.5` | 0.5 (explicit) |
| `1.0` | 1.0 (explicit, 100% sampling) |
| `0.0` | 0.0 (explicit, все span'ы сбрасываются ratio-правилом) |

**Последствие для интеграционных тестов:** тесты, использующие `AutoConfiguredOpenTelemetrySdk` (в котором `PlatformAutoConfigurationCustomizer` загружается через ServiceLoader) и при этом рассчитывающие на 100% sampling без явного `platform.tracing.sampling.ratio`, были исправлены в PR-5: `BspDropOldestNoDoubleExportTest` и `BspReplacementSpikeTest` явно выставляют `platform.tracing.sampling.ratio=1.0`.

Подробности: `sampling-ratio-drift-decision-note.md`.

---

## 9. Resource Decision

`PlatformResourceProvider` и `SafeResourceProvider` остаются прямыми читателями `ConfigProperties`.

**Решение:** `RESOURCE_R0_SPI_EXCEPTION_WITH_GUARDRAILS`

Причины отказа от миграции в A3' (кратко):
- `ResourceProvider` создаётся через `ServiceLoader` с no-arg constructor — bootstrap `ExtensionConfig` недоступен.
- `service.*` свойства (ключи OTel semantic conventions) потенциально конфликтуют с `ExtensionPropertyNames.RESOURCE_*` — риск семантического смешения.
- Решение RESOURCE_R0 стабилизирует SPI-границу без дополнительного риска.

Допустимая альтернатива в будущем: `addResourceCustomizer` hook OTel SDK (минует ServiceLoader), если API появится в используемой версии.

Подробности: `resource-extension-config-spi-boundary-decision.md`.

---

## 10. Scrubbing Final State

После PR-4 и PR-4B scrubbing startup полностью мигрирован:

- Все scrubbing startup-значения (`enabled`, `builtInRules`, `hmacKey`, `missingKeyPolicy`, `hashKeyId`, `rulesConfig`, `rulesExtensions`, `rulesValidationMode`) приходят из `ScrubbingExtensionConfig`.
- `PlatformSpanProcessorFactory` не читает scrubbing-domain свойства через `ConfigProperties` напрямую.
- `ScrubbingRulesLoader.load(String, ClassLoader)` стал `public` (PR-4B): `PlatformSpanProcessorFactory` передаёт `scrubbing.rulesConfig()` непосредственно, минуя `load(ConfigProperties)`.
- Единственный оставшийся `ConfigProperties` в `PlatformSpanProcessorFactory` — `JavaAgentExtensionPaths.resolveRawValue(config)` (читает `otel.javaagent.extensions`): OTel Java Agent SPI-путь, не scrubbing-домен, явно задокументирован в javadoc метода.

---

## 11. Guardrails

### G1 — Domain configs не зависят от ConfigProperties

**Тест:** `ExtensionNoSpringDependencyArchTest.domain_configs_do_not_depend_on_ConfigProperties`  
**Правило:** классы с суффиксом `*ExtensionConfig`, кроме самого `ExtensionConfig`, не должны зависеть от `io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties`.  
`ExtensionConfig` и `ExtensionConfigReader` явно исключены.

### G2 — Нет Spring-зависимости в otel-extension

**Тест:** `ExtensionNoSpringDependencyArchTest.расширение_не_зависит_от_spring`  
**Правило:** ни один production-класс в `space.br1440.platform.tracing.otel.javaagent` не должен зависеть от `org.springframework..`.

### G3 — Нет descriptor registry / codegen framework

**Тест:** `ExtensionConfigBootstrapGuardrailsArchTest.нет_descriptor_registry_классов`  
**Правило:** ни один production-класс в otel-extension не должен иметь имя, оканчивающееся на `PropertyKey` или содержащее `PropertyRegistry`, поскольку это запрещённые паттерны A3' архитектуры.  
**Дополнительный grep-гард:** `rg "PropertyKey|codegen|generated metadata|reflection config" platform-tracing-otel-javaagent-extension/src/main/java` — ожидается: 0 matches (только javadoc комментарий в `ExtensionConfigReader` допустим).

### G4 — Конструктор ExtensionConfig только в разрешённых местах

**Тест:** `ExtensionConfigBootstrapGuardrailsArchTest.только_разрешённые_классы_строят_ExtensionConfig`  
**Правило:** только `PlatformAutoConfigurationCustomizer` (bootstrap owner) и `PlatformSamplerProvider` (named SPI exception) могут вызывать `new ExtensionConfig(ConfigProperties)`. Ни один другой production-класс этого не должен делать.

### G5 — Мигрированные фабрики не читают мигрированные domain-свойства напрямую

**Grep-гард (не ArchUnit):**

```bash
rg "ExtensionPropertyNames\.(SAMPLING|SCRUBBING|QUEUE|METRICS|ENRICHING|BAGGAGE|SDK|CLASSIFICATION|WATCHDOG|VALIDATION)" \
  platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/factory \
  platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/sampler
```

**Ожидаемый результат:** 0 matches. Верифицирован по состоянию на PR-5.

Исключения из правила G5:
- `configuration` пакет (`ExtensionConfig`, `ExtensionConfigReader`, domain config классы) — разрешены.
- `resource` пакет (`PlatformResourceProvider`, `SafeResourceProvider`) — SPI exception, допустимо.
- OTel SDK ключи (`otel.bsp.*`, `otel.javaagent.extensions`) — не mигрированные domain свойства, разрешены.

---

## 12. Validation Commands

```bash
# Запустить все тесты otel-extension
./gradlew :platform-tracing-otel-javaagent-extension:test

# Запустить architecture fitness suite
./gradlew pr4ArchitectureFitnessVerify

# G4: new ExtensionConfig только в двух разрешённых местах
rg "new ExtensionConfig" platform-tracing-otel-javaagent-extension/src/main
# Ожидается: PlatformAutoConfigurationCustomizer + PlatformSamplerProvider

# G1: domain config классы не держат ConfigProperties
rg "ConfigProperties" platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration
# Ожидается: только ExtensionConfig, ExtensionConfigReader, SamplingExtensionConfig (в javadoc), etc.

# G5: мигрированные фабрики не читают мигрированные domain-свойства
rg "ExtensionPropertyNames\.(SAMPLING|SCRUBBING|QUEUE|METRICS|ENRICHING|BAGGAGE|SDK|CLASSIFICATION|WATCHDOG|VALIDATION)" \
  platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/factory \
  platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/sampler
# Ожидается: 0 matches

# Runtime API не изменился
rg "RuntimeConfigApplier|updateSamplingPolicy|PlatformTracingControlMBean|TracingProperties" \
  platform-tracing-otel-javaagent-extension platform-tracing-spring-boot-autoconfigure
# Ожидается: только существующие определения и тесты, без новых production call sites

# G3: нет descriptor/codegen классов
rg "PropertyKey|codegen|generated metadata|reflection config" \
  platform-tracing-otel-javaagent-extension/src/main/java
# Ожидается: только javadoc комментарий в ExtensionConfigReader (не класс-имя)
```

---

## 13. Future Work

| Задача | Приоритет | Связь с A3' |
|---|---|---|
| Опциональный `ResourceProvider` redesign через `addResourceCustomizer` OTel SDK hook | Низкий | Отдельный PR, не требуется для A3' |
| Использование `forceRecordHeader` / `qaHeader` из `SamplingExtensionConfig` в sampling policy | По необходимости | Уже доступны в domain config |
| F3 RateLimit feature (отдельный спайк-план) | По roadmap | Не влияет на A3'; SamplingPolicyEngine extensible |

A3' refactoring считается **завершённым** после PR-6. Дальнейший рефакторинг в этой области не требуется.

---

## 14. Final Status

| | |
|---|---|
| A3' code refactoring | ✅ Завершён (PR-1 → PR-5) |
| Architecture closure | ✅ Завершён (PR-6) |
| Guardrails G1, G2 | ✅ Существуют с PR-2 |
| Guardrails G3, G4 | ✅ Добавлены в PR-6 |
| Guardrail G5 | ✅ Верифицирован grep; documented |
| OTel SPI exceptions | ✅ Задокументированы и протестированы |
| Runtime/JMX boundary | ✅ Не затронуто |
| Behavior change | ✅ Единственное: `ALIGN_TO_EXTENSION_DEFAULTS` — `sampling.ratio` absent/blank → 0.1 |

**Следующий шаг:** architecture review и merge. Дальнейший рефакторинг в рамках ExtensionConfig A3' не требуется.
