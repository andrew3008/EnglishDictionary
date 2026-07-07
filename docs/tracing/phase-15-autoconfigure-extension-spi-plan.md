# Фаза 15. OpenTelemetry Java autoconfigure и extension SPI — детальный план

| Поле | Значение |
|------|----------|
| Тип документа | Пошаговый план реализации (pre-production v0.1.0) |
| Дата | 2026-06-09 |
| Источники | `Фаза 15.md` (теория), `Traces Requests.txt` (требования §3/§4), `E:\Platform_Traces_Examples\src` (opentelemetry-java / opentelemetry-java-instrumentation), `docs/decisions/*` (ADR) |
| Стек | OTel SDK BOM 1.62.0, OTel instrumentation/agent 2.28.1, Spring Boot 3.5.5, Java 21 |
| Статус | **Реализовано** (2026-06-09) |

---

## 0. TL;DR и критическая рамка (не принимаем теорию вслепую)

Теория Фазы 15 описывает **два режима**: (1) starter-managed programmatic SDK и (2) Java Agent / SDK autoconfigure mode, и предлагает named SPI для sampler/propagator/exporter, mode-detection (`platform.tracing.sdk.mode`), compatibility matrix и precedence-документацию.

**Что уже сделано в проекте** (из gap-анализа кода): `platform-tracing-otel-extension` уже реализует agent/autoconfigure-путь через `AutoConfigurationCustomizerProvider` + `ResourceProvider`, framework-agnostic `CompositeSampler`, `PlatformTraceControlPropagator`, чтение через `ConfigProperties`, per-key omit precedence для resource. Extension **не зависит от Spring**. OTel BOM 1.62.0 / Agent 2.28.1, есть `otel-compatibility-matrix.md` и `verifyOtelBomAlignment`.

**Главное расхождение теории с действующими ADR.** `ADR-otel-direct-integration` фиксирует **agent-first** production-модель и прямо запрещает starter'у создавать второй `SdkTracerProvider`; «SDK-only path без Java Agent» помечен **Deferred (P2)**. Поэтому:

- ❌ **НЕ строим** полноценное programmatic-создание `SdkTracerProvider` в starter'е (это противоречит ADR и порождает риск двойного SDK — anti-goal из самой же Фазы 15).
- ✅ **Фокус Фазы 15 для ЭТОГО проекта** = завершить и формализовать agent/autoconfigure SPI-поверхность — **выполнено**:
  1. **Named sampler SPI** (`otel.traces.sampler=platform`) — ✅ `PlatformSamplerProvider` + idempotency-guard.
  2. **Named propagator SPI** (`otel.propagators=...,platform-trace-control`) — ✅ `PlatformTraceControlPropagatorProvider` + ENV-aware дефолт.
  3. **Mode detection + double-SDK guard** — ✅ `SdkModeResolver`, actuator `sdk`, `NoOp` только для `DISABLED`.
  4. **Precedence-документация** — ✅ `ADR-config-precedence.md`.
  5. **Compatibility matrix** — ✅ раздел «Extension SPI» в `otel-compatibility-matrix.md`.
  6. **ArchUnit-инвариант** «extension не импортирует Spring» — ✅ `ExtensionNoSpringDependencyArchTest`.
- 🟡 **«starter-managed SDK»** трактуем как **already-existing consume-mode**: starter не создаёт SDK, а потребляет `OpenTelemetry`/`GlobalOpenTelemetry` (agent или тест). Полноценный programmatic bootstrap — отдельный future ADR, вне Фазы 15.

Итог: Фаза 15 — это **не «добавить второй режим с нуля»**, а **закрыть SPI-gap'ы agent-режима + сделать режимы явными и диагностируемыми**, оставаясь в рамках agent-first.

---

## 1. Gap-анализ (baseline → после реализации)

| Область | Было (baseline) | Стало (после Фазы 15) |
|---|---|---|
| SPI registrations | только `AutoConfigurationCustomizerProvider` + `ResourceProvider` | **4 SPI**: +`PlatformSamplerProvider`, +`PlatformTraceControlPropagatorProvider`; `verifyExtensionSpiRegistration` |
| Sampler wiring | inline `addSamplerCustomizer` (compose с `existing`) | `otel.traces.sampler=platform` работает; idempotency-guard через `PlatformManagedSampler` |
| Propagator wiring | inline always-append `PlatformTraceControlPropagator` | named SPI + `PlatformPropagatorsDefaultsCustomizer` (`addPropertiesCustomizer`); always-append удалён |
| `platform.tracing.sdk.mode` | отсутствует | `TracingProperties.Sdk.mode` + `SdkModeResolver` + actuator `sdk` + agent-side лог |
| Starter создаёт SDK | нет (agent-first) | формализовано: starter не создаёт SDK; `NoOp` только для `DISABLED` |
| Agent detection | `OtelAgentDetector` — только WARN | используется в `SdkModeDiagnostics` + actuator `agentDetected` |
| Spring в extension | нет compile-зависимости | ArchUnit `ExtensionNoSpringDependencyArchTest` |
| Resource precedence | per-key omit, OTEL wins | `ADR-config-precedence.md`; инвариант `ResourceMergeIntegrationTest.otel_service_name_побеждает_platform` |
| Compatibility matrix | только versions | раздел «Extension SPI surface» + fallback-таблица |
| ConfigProperties | extension читает только ConfigProperties | без изменений; инвариант закреплён ArchUnit |

Ключевые файлы (после реализации):
- `PlatformAutoConfigurationCustomizer` — `addPropertiesSupplier`, **`addPropertiesCustomizer`** (propagator defaults + sdk.mode diagnostics), `addSamplerCustomizer`, `addPropagatorCustomizer` (только baggage).
- `sampler/PlatformSamplerBuilder`, `sampler/PlatformSamplerProvider`, `sampler/PlatformManagedSampler`, `sampler/PlatformManagedSamplers` — единый builder + named SPI + idempotency.
- `propagation/PlatformTraceControlPropagatorBuilder`, `propagation/PlatformTraceControlPropagatorProvider`, `propagation/PlatformPropagatorsDefaultsCustomizer` — named SPI + ENV-aware дефолт.
- `factory/PlatformSamplerFactory` — idempotency-guard + JMX re-wire; `factory/PlatformPropagatorFactory` — только `FilteringBaggagePropagator`.
- `configuration/ExtensionPropertyNames` — добавлен `SDK_MODE` (historically introduced via the deleted transitional configuration facade).
- Starter: `support/SdkMode`, `SdkModeResolver`, `SdkModeDiagnostics`; `TracingCoreAutoConfiguration` — facade vs NoOp; `TracingActuatorEndpoint` — секция `sdk`.
- `platform-tracing-otel-extension/build.gradle` — задача `verifyExtensionSpiRegistration`.

---

## 2. Маппинг требований (`Traces Requests.txt`) → Фаза 15

Большинство требований (span-лимиты, scrubbing, BSP drop-oldest, экспорт, retry/backpressure) закрыто прошлыми фазами. К Фазе 15 относятся:

| Требование | Пункт | Связь с Фазой 15 |
|---|---|---|
| Конфигурируемость без изменения кода | §4 | Чтение всех настроек через `ConfigProperties` (env/sysprop) в agent-режиме; named SPI включается через `otel.*` |
| Динамическое переключение «на лету» (env / config) | §4 | `platform.tracing.sdk.mode`; runtime ratio через `SamplerStateHolder`/JMX (уже есть) — named sampler должен переиспользовать тот же holder, а не фиксировать ratio на старте |
| Передача контекста (trace_id/span_id/flags) + `X-Trace-On` | §3 | Named propagator SPI `platform-trace-control`; W3C — зона Агента (не заменяем) |
| Обязательные сэмплы на ошибки, общие — без сэмплирования, sampling на Collector | §3 / параметры | Named sampler переиспользует `CompositeSampler` (force/QA/drop/route/ratio); tail-sampling — Collector (вне фазы) |
| Ручка для QA → Request ID в заголовке | параметры | Уже `X-QA-Trace` + `X-Request-Id` (Фаза 12); propagator SPI делает это discoverable через `otel.propagators` |
| Precedence идентичности (OTEL_SERVICE_NAME и т.д.) | §1 | Best Practice Фазы 15 §9 «Документировать precedence» → единый контракт (ADR) |

**Вывод:** Фаза 15 не добавляет новых runtime-возможностей по требованиям — она делает существующую platform policy **доступной и в чисто-agent/autoconfigure окружении** (через named `otel.*`), что и есть требование §4 «без изменения кода приложения».

---

## 3. Анализ примеров (`E:\Platform_Traces_Examples\src`) — что перенять

Источники: `opentelemetry-java/sdk-extensions/autoconfigure-spi` (контракты SPI), `opentelemetry-java/sdk-extensions/autoconfigure` (resolution engine), `opentelemetry-java-instrumentation/examples/extension` (эталонный extension jar), production-провайдеры (`jaeger-remote-sampler`, `trace-propagators/B3`).

### 3.1. Named `ConfigurableSamplerProvider` — паттерн для `PlatformSamplerProvider`
Эталон: `examples/extension/.../DemoConfigurableSamplerProvider.java` и production `JaegerRemoteSamplerProvider.java`:
```java
public class DemoConfigurableSamplerProvider implements ConfigurableSamplerProvider {
  @Override public Sampler createSampler(ConfigProperties config) { return new DemoSampler(); }
  @Override public String getName() { return "demo"; }   // → otel.traces.sampler=demo
}
```
Resolution engine (`TracerProviderConfiguration.configureSampler`): built-in имена зарезервированы (`always_on`, `traceidratio`, `parentbased_*`, ...); неизвестное имя → `ConfigurationException`. **Перенять:** имя `platform`; `createSampler(config)` читает `force-values`/`drop-paths`/`ratio`/`route-ratios` из `ConfigProperties` и строит `CompositeSampler` через тот же `SamplerStateHolder` (runtime ratio сохраняется).

### 3.2. Named `ConfigurablePropagatorProvider` — паттерн для `platform-trace-control`
Эталон: `trace-propagators/.../B3ConfigurablePropagator.java`:
```java
public final class B3ConfigurablePropagator implements ConfigurablePropagatorProvider {
  @Override public TextMapPropagator getPropagator(ConfigProperties config) { return B3Propagator.injectingSingleHeader(); }
  @Override public String getName() { return "b3"; }     // → otel.propagators=...,b3
}
```
Resolution engine (`PropagatorConfiguration.configurePropagators`): читает `otel.propagators`, built-in `tracecontext`/`baggage`/`none` особые, остальные — через SPI; `none` должен быть один. **Перенять:** имя `platform-trace-control`; `getPropagator(config)` строит `PlatformTraceControlPropagator` с именами заголовков из `PropagationDefaults`.

### 3.3. `addPropertiesSupplier` для дефолтов (низший приоритет)
Эталон: `DemoAutoConfigurationCustomizerProvider.getDefaultProperties()` ставит `otel.traces.sampler=demo`. **Перенять с осторожностью:** дефолт `otel.propagators` / `otel.traces.sampler` ставим **только если пользователь не задал** — иначе перетрём оператора. Это решает проблему двойной регистрации (см. §5).

### 3.4. Анти-паттерн (НЕ перенимать)
`examples/distro/custom/DemoAutoConfigurationCustomizerProvider` делает `.setSampler(new DemoSampler())` прямо в tracer-customizer. SPI-javadoc предупреждает: `setSampler` в tracer-customizer **отключает** все `addSamplerCustomizer`. **Не повторяем** — используем named provider + `addSamplerCustomizer` для декорации.

### 3.5. ResourceProvider precedence (подтверждение нашего подхода)
Эталоны `AttributeResourceProvider.shouldUpdate` и `JarServiceNameDetector`: `service.name` пишется только если `otel.service.name` не задан и текущее значение — дефолтный `unknown_service:java`. **Наш `ResourceAttributeResolver` уже делает это** (per-key omit) — подтверждает корректность, менять не нужно.

### 3.6. Тест-паттерны
`AutoConfiguredOpenTelemetrySdk.builder().addPropertiesSupplier(...).build()` для интеграции; `DefaultConfigProperties.createFromMap(Map)` для unit-тестов SPI-провайдеров в изоляции; `ServiceLoader`-based проверки регистрации. **Перенять оба уровня** (unit на `createFromMap` + интеграция на реальном autoconfigure).

---

## 4. ADR-выравнивание (ограничения и зависимости)

| ADR | Что фиксирует | Влияние на план |
|---|---|---|
| `ADR-otel-direct-integration` | agent-first; запрет второго SDK; «SDK-only» Deferred P2; реализуем официальные SPI | Запрещает programmatic starter-SDK. Named sampler/propagator SPI — это **прямая реализация официальных SPI** = в духе ADR ✅ |
| `ADR-sampler-compose` | compose `existing` vs platform ratio; `MutableRatioSampler` уже parentBased; force/QA через CompositeSampler | Named sampler НЕ должен ломать compose-логику. Reconciliation в §5.1 |
| `ADR-context-first-propagation` | `PlatformTraceControlPropagator` извлекает X-Trace-On/QA/Request-Id в Context; W3C не заменяем | Named propagator = тот же класс, просто discoverable через `otel.propagators` |
| `ADR-resource-merge-precedence` | per-key omit, `order()=100`, OTEL_* wins, `ConditionalResourceProvider` | Precedence-документация Фазы 15 = свод этого ADR + dual-mode таблица |
| `ADR-platform-resource-override` | superseded; `order()=0`→`100` | Не трогаем; ссылка для истории |
| `ADR-dual-channel-properties-v0.1` | Spring `TracingProperties` ↔ Agent configuration (`ExtensionPropertyNames`, `PlatformTracingDefaultsProvider`); bridge Spring→`-Dotel.*` отвергнут (overengineering) | Подтверждает: extension читает только ConfigProperties/env, НЕ Spring. Mode `external`/`agent` = Agent-канал |
| `ADR-safe-span-exporter-v1` / `ADR-drop-oldest-export-processor-v1` | SafeSpanExporter + DropOldest реализованы | Exporter SPI **не нужен** (используем OTLP + wrapper) — соответствует Фазе 15 §7 |

**Новые ADR Фазы 15 (deliverables, см. §11):**
1. `ADR-sdk-mode-detection.md` — режимы `agent|starter|external|disabled`, как детектируем, double-SDK guard.
2. `ADR-named-spi-sampler-propagator.md` — named `platform` sampler + `platform-trace-control` propagator; reconciliation с inline-customizer'ами; дефолт propagator через `addPropertiesCustomizer` (ENV-aware); idempotency-guard sampler через маркер `PlatformManagedSampler`.
3. `ADR-config-precedence.md` (или раздел в существующем) — единый precedence-контракт identity/policy.

---

## 5. Ключевые архитектурные решения (требуют аккуратности)

### 5.1. Reconciliation named sampler ↔ `addSamplerCustomizer` (КРИТИЧНО)
Сейчас `CompositeSampler` всегда навешивается через `addSamplerCustomizer` (compose с `existing`). Если просто добавить named `PlatformSamplerProvider`, при `otel.traces.sampler=platform` получим **двойную композицию** (named построит CompositeSampler, потом customizer ещё раз обернёт).

**Решение (idempotent compose):**
- Добавить `PlatformSamplerProvider implements ConfigurableSamplerProvider`, `getName()="platform"`, `createSampler(config)` строит тот же `SafeSampler(CompositeSampler(holder), fallback)` через общую фабрику-метод (вынести логику `PlatformSamplerFactory.buildSampler` в reusable `PlatformSamplerBuilder.build(config)`).
- В `addSamplerCustomizer` добавить **idempotency-guard через маркер-интерфейс** (НЕ статический `AtomicBoolean` — см. ниже): ввести маркер `PlatformManagedSampler` (реализуют `SafeSampler` и `CompositeSampler`); если `existing` (или, для надёжности, любой в цепочке по `getDescription()`) уже `PlatformManagedSampler` → вернуть `existing` без повторной обёртки.
  - **Почему НЕ `AtomicBoolean` в фабрике** (контр-аргумент к ревью архитектора): SDK autoconfigure может строиться **несколько раз в одном JVM** (unit/slice-тесты, несколько `AutoConfiguredOpenTelemetrySdk`, повторная инициализация). Статический флаг выставится на первой сборке и **ошибочно пропустит** последующие → скрытый баг и flaky-тесты. Маркер-интерфейс — stateless и корректен при многократной конфигурации.
  - **Почему top-level проверки достаточно (в основном потоке):** вывод named-провайдера передаётся в `addSamplerCustomizer` как `existing` напрямую — SDK не вставляет промежуточных обёрток между resolution named-сэмплера и применением customizer'ов. Рекурсивная проверка по `getDescription()` — defense-in-depth на случай стороннего декоратора.
- Поведение:
  - `otel.traces.sampler=platform` → named provider строит платформенный sampler; customizer видит `PlatformManagedSampler` → no-op. ✅
  - `otel.traces.sampler` не задан / `parentbased_traceidratio` / canary → named provider не вызывается; customizer композирует CompositeSampler поверх `existing` (текущее поведение по `ADR-sampler-compose`). ✅
- **Не** ставим `otel.traces.sampler=platform` дефолтом в v0.1.0 (сохраняем текущее «compose-over-existing» как default; named — явный opt-in). Это консервативно и не меняет рантайм для существующих деплоев.
- Runtime ratio: и named, и inline путь используют **один** `SamplerStateHolder` (через JMX) — требование §4 «на лету» сохранено (не одноразовый объект с фиксированным ratio).

### 5.2. Reconciliation named propagator ↔ always-append customizer (КРИТИЧНО)
Сейчас `PlatformPropagatorFactory` всегда добавляет `PlatformTraceControlPropagator` (AtomicBoolean — единожды). Если добавить named `platform-trace-control` И пользователь укажет его в `otel.propagators`, получим дубль.

**Решение:**
- Добавить `PlatformTraceControlPropagatorProvider implements ConfigurablePropagatorProvider`, `getName()="platform-trace-control"`.
- Дефолт: через **`addPropertiesCustomizer(Function<ConfigProperties, Map<String,String>>)`** (НЕ `addPropertiesSupplier` — см. ниже) дописать `platform-trace-control` в `otel.propagators` **только если отсутствует и не `none`**. Алгоритм: прочитать **уже смерженное** `config.getList("otel.propagators")` (default `tracecontext,baggage`); если `none` → ничего не возвращаем; если содержит `platform-trace-control` → ничего; иначе вернуть `Map.of("otel.propagators", join(existing, "platform-trace-control"))`.
  - **Почему `addPropertiesCustomizer`, а не `addPropertiesSupplier`** (правка по ревью архитектора, принято): `addPropertiesSupplier` даёт значения **низшего приоритета** и не видит итоговый merge — если оператор задал `otel.propagators` через ENV/sysprop, supplier-дефолт будет **проигнорирован**, и `platform-trace-control` не добавится. `addPropertiesCustomizer` читает **итоговый** `ConfigProperties` (вкл. ENV) и возвращает **override** — корректное «add if absent». Эталон upstream: `ResourceProviderPropertiesCustomizer` использует именно `addPropertiesCustomizer` для деривации конфигурации из существующих свойств.
- Убрать always-append из `addPropagatorCustomizer` (его роль берёт named provider + property-customizer). **Оставить** в `addPropagatorCustomizer` только baggage-обёртку `FilteringBaggagePropagator` (она декорирует `W3CBaggagePropagator`, а не добавляет платформенный — это другой механизм).
- Idempotency: named provider возвращает singleton; логика property-customizer'а исключает дубль; даже при случайном двойном указании composite OTel не падает.
- Поведение:
  - Дефолт (пользователь не трогал `otel.propagators`) → `tracecontext,baggage,platform-trace-control`. ✅ (как требует Фаза 15)
  - Явный `otel.propagators=tracecontext,baggage,platform-trace-control` → ровно один platform-trace-control. ✅
  - `otel.propagators=none` → платформенный не добавляется (оператор отключил всё). ✅

### 5.3. Mode detection (`platform.tracing.sdk.mode`)
- Свойство-enum: `agent | starter | external | disabled`, default `auto` (детект).
- Детект (если `auto`): агент присутствует (`OtelAgentDetector.isAgentPresent()` или `GlobalOpenTelemetry.isSet()` с функциональным SDK) → `agent`; иначе если есть пользовательский `OpenTelemetry` bean → `external`; иначе → `starter` (consume-mode, БЕЗ создания SDK); `disabled` — только по явному значению.
- **Назначение — диагностика и guard, НЕ создание SDK:**
  - Логируется один раз на старте (mode + признак: agent/global/bean).
  - В `agent`/`external` режиме starter гарантированно НЕ создаёт `OpenTelemetry` SDK (усиление `@ConditionalOnMissingBean(OpenTelemetry.class)` + WARN, если кто-то попытался).
  - **`NoOpPlatformTracing` — только для `disabled`** (правка по ревью архитектора, принято). В `agent`/`external` starter НЕ уходит в NoOp: он поднимает **фасадные бины** `PlatformTracing`, делегирующие в `GlobalOpenTelemetry.get()` / пользовательский `OpenTelemetry` bean. Так бизнес-код приложения сохраняет рабочий `PlatformTracing` поверх агентского SDK.
  - **Примечание:** проект уже делает это в `TracingCoreAutoConfiguration` — `platformTracing(...)` возвращает `DefaultPlatformTracing(openTelemetry, policy)` поверх consume-нутого `OpenTelemetry`/`GlobalOpenTelemetry`, а `NoOpPlatformTracing` отдаётся лишь при отсутствии функционального SDK. PR-3 лишь делает это **явным через режимы** (mode→facade vs mode→noop) и закрывает edge-case (`disabled`). Платформа не обязана отдавать «сырые» OTel `Tracer`/`Meter` бины — за них в agent-first отвечает агент/micrometer-bridge, потребляющие global.
- Свойство читается **двумя каналами**: Spring `TracingProperties.sdk.mode` (UX) и extension `ConfigProperties` `platform.tracing.sdk.mode` (диагностика на стороне Agent). Согласовано с `ADR-dual-channel-properties`.

### 5.4. ConfigProperties-only в extension (инвариант)
Extension НЕ читает Spring `Environment`. Все platform-specific ключи — через `ConfigProperties` (+ `PLATFORM_TRACING_*` env bridge в `PlatformTracingDefaultsProvider`). Закрепить ArchUnit-правилом (запрет импорта `org.springframework.*` в `platform-tracing-otel-extension`).

---

## 6. Целевая архитектура (после Фазы 15)

```
                 ┌──────────────────────── platform-tracing-otel-extension (no Spring) ───────────────────────┐
Java Agent ──►   │ META-INF/services:                                                                          │
SDK autoconfigure│   AutoConfigurationCustomizerProvider → PlatformAutoConfigurationCustomizer                 │
      │          │   ResourceProvider                    → SafeResourceProvider                                │
      ▼          │   ConfigurableSamplerProvider         → PlatformSamplerProvider        (NEW)  name=platform │
 ConfigProperties│   ConfigurablePropagatorProvider      → PlatformTraceControlPropagatorProvider (NEW)        │
      │          │                                          name=platform-trace-control                        │
      ▼          │ Shared builders (framework-agnostic):                                                       │
 addPropertiesCustomizer (defaults: otel.propagators += platform-trace-control if absent, ENV-aware)           │
 addSamplerCustomizer (idempotent wrap via PlatformManagedSampler marker)                                      │
 addPropagatorCustomizer (baggage FilteringBaggagePropagator only)                                            │
 addTracerProviderCustomizer (span processors chain)                                                          │
 addSpanExporter/ProcessorCustomizer (SafeSpanExporter + DropOldest)                                          │
                 └─────────────────────────────────────────────────────────────────────────────────────────┘
                              ▲ один и тот же core: CompositeSampler, rules, PlatformTraceControl
                              ▼
 Spring Boot starter (consume-mode): TracingCoreAutoConfiguration → DefaultPlatformTracing(OpenTelemetry|Global)
                                     + sdk.mode diagnostics + @ConditionalOnMissingBean(OpenTelemetry) guard
```

Принцип: **policy в core, обе точки входа (Spring consume / Agent SPI) переиспользуют один core**; programmatic SDK-bootstrap — вне Фазы 15.

---

## 7. Пошаговый план (PR-разбивка)

> Каждый PR: framework-agnostic код в `platform-tracing-otel-extension` (+ `-api`/`-core` при необходимости), unit на `DefaultConfigProperties.createFromMap`, ArchUnit где уместно. Комментарии в коде — на русском. Кодировку не трогаем. `@Deprecated` не добавляем (pre-production).

### PR-0. Подготовка: вынести reusable builders + ConfigProperties-инвариант ✅
**Цель:** убрать дублирование логики между inline-customizer'ами и будущими named SPI.
- Вынести `PlatformSamplerFactory.buildSampler(...)` → `sampler/PlatformSamplerBuilder.build(ConfigProperties): Sampler` (статическая/инстанс-фабрика, читает force/drop/ratio/route, создаёт `SamplerStateHolder` + `CompositeSampler` + `SafeSampler`). `PlatformSamplerFactory` делегирует в него.
- Вынести построение `PlatformTraceControlPropagator` → `propagation/PlatformTraceControlPropagatorBuilder.build(ConfigProperties): TextMapPropagator` (имена заголовков из `PropagationDefaults`).
- ArchUnit: новый тест `ExtensionNoSpringDependencyArchTest` — классы `platform-tracing-otel-extension` не зависят от `org.springframework..`.
- **Тесты:** существующие зелёные; новый ArchUnit зелёный.
- **Риск:** низкий (рефактор без смены поведения).

### PR-1. Named `ConfigurableSamplerProvider` (`platform`) ✅
**Цель:** `otel.traces.sampler=platform` поднимает `CompositeSampler`.
- `sampler/PlatformSamplerProvider implements ConfigurableSamplerProvider`:
```java
public final class PlatformSamplerProvider implements ConfigurableSamplerProvider {
    @Override public Sampler createSampler(ConfigProperties config) {
        // Тот же builder, что и inline-путь — единый источник истины
        return PlatformSamplerBuilder.build(config);
    }
    @Override public String getName() { return "platform"; }
}
```
- Регистрация: `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider`.
- **Idempotency-guard через маркер-интерфейс** в inline-customizer: ввести `PlatformManagedSampler` (реализуют `SafeSampler`/`CompositeSampler`); если `existing instanceof PlatformManagedSampler` (или цепочка по `getDescription().contains("Platform...")` как defense-in-depth) → вернуть `existing` без повторной обёртки. **НЕ использовать статический `AtomicBoolean`** — он ломается при многократной autoconfigure-сборке в одном JVM (тесты/несколько SDK); маркер stateless и корректен (обоснование в §5.1).
- **НЕ** ставим `otel.traces.sampler=platform` дефолтом (named — явный opt-in; compose-over-existing остаётся default по `ADR-sampler-compose`).
- **Тесты:**
  - unit `createSampler_builds_composite` (`DefaultConfigProperties.createFromMap`).
  - unit `inline_customizer_idempotent_when_existing_is_platform` — no double-wrap.
  - integration `otel_traces_sampler_platform_resolves` через `AutoConfiguredOpenTelemetrySdk.builder().addPropertiesSupplier(()->Map.of("otel.traces.sampler","platform","otel.traces.exporter","none"))` (здесь supplier уместен — это тестовый ввод, а не дефолт-логика).
  - unit `idempotent_double_build_does_not_skip` — повторная autoconfigure-сборка в одном JVM снова поднимает платформенный sampler (регресс-защита против статического флага).
  - регресс `ForceSamplingAgentSmokeTest`, `CompositeSampler*Test` — зелёные.
- **Риск:** средний (двойная композиция) → закрыт idempotency-guard'ом + тестом.

### PR-2. Named `ConfigurablePropagatorProvider` (`platform-trace-control`) ✅
**Цель:** `otel.propagators=tracecontext,baggage,platform-trace-control` работает; дефолт добавляет его автоматически.
- `propagation/PlatformTraceControlPropagatorProvider implements ConfigurablePropagatorProvider`, `getName()="platform-trace-control"`, `getPropagator(config)=PlatformTraceControlPropagatorBuilder.build(config)`.
- Регистрация: `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider`.
- В `PlatformAutoConfigurationCustomizer`: **`addPropertiesCustomizer`** (НЕ `addPropertiesSupplier`) дописывает `platform-trace-control` в `otel.propagators` **только если** отсутствует и не `none` — читает уже смерженный `ConfigProperties` (вкл. ENV) и возвращает override (обоснование в §5.2; эталон upstream — `ResourceProviderPropertiesCustomizer`).
- Убрать always-append `PlatformTraceControlPropagator` из `addPropagatorCustomizer`; оставить только baggage-`FilteringBaggagePropagator`-обёртку.
- **Тесты:**
  - unit `getPropagator_extracts_X-Trace-On` (carrier → Context `PlatformTraceControl`).
  - integration `default_propagators_include_platform` (пользователь не трогал `otel.propagators`).
  - integration `env_propagators_still_get_platform` — `otel.propagators` задан через property-source (имитация ENV); platform-trace-control всё равно дописан (регресс-защита против `addPropertiesSupplier`, который бы проигнорировался).
  - integration `explicit_propagators_no_duplicate` (`otel.propagators=tracecontext,baggage,platform-trace-control` → один экземпляр).
  - integration `propagators_none_excludes_platform`.
  - регресс `PlatformTraceControlPropagatorTest`, baggage-лимиты — зелёные.
- **Риск:** средний (двойная регистрация) → закрыт дефолт-логикой + тестами.

### PR-3. Mode detection + double-SDK guard ✅
**Цель:** явные режимы и диагностика, без создания SDK.
- `TracingProperties`: добавить `Sdk { Mode mode = AUTO }` (`AUTO|AGENT|STARTER|EXTERNAL|DISABLED`), ключ `platform.tracing.sdk.mode`.
- `extension`: `ExtensionPropertyNames` + diagnostics — прочитать `platform.tracing.sdk.mode` из `ConfigProperties` для лог-строки на старте Agent-режима.
- Starter: `support/SdkModeResolver` — резолв режима (см. §5.3); `TracingCoreAutoConfiguration` логирует mode один раз; усилить guard `@ConditionalOnMissingBean(OpenTelemetry.class)` + WARN при попытке создать SDK в `agent`/`external`.
- **Фасад vs NoOp (явное разграничение):** `agent`/`external` → starter поднимает `PlatformTracing`-фасад (`DefaultPlatformTracing`), делегирующий в `GlobalOpenTelemetry.get()` / пользовательский `OpenTelemetry` bean (как уже сейчас); `disabled` → `NoOpPlatformTracing`. NoOp **только** для `disabled`.
- Actuator: добавить `sdkMode` + `agentDetected` в `/actuator/tracing` (через `OtelEnvHintsBuilder`/diagnostics).
- **Тесты:**
  - unit `resolver_agent_when_global_set`, `resolver_external_when_user_bean`, `resolver_starter_when_none`, `disabled_yields_noop`.
  - slice `agent_mode_does_not_create_sdk` (контекст с mock global → нет второго `OpenTelemetry` bean).
  - slice `agent_mode_provides_facade_not_noop` — в режиме `agent` `PlatformTracing` делегирует в global, а не `NoOpPlatformTracing`.
- **Риск:** низкий (диагностика; SDK и так не создаётся).

### PR-4. Resource precedence — единый контракт (документация + проверки) ✅
**Цель:** свести precedence в один контракт и закрепить тестом.
- Свести в `ADR-config-precedence.md` (или раздел SUPPORTED): `OTEL_SERVICE_NAME`/`OTEL_RESOURCE_ATTRIBUTES`/`-Dotel.*` > `platform.tracing.service.*`/Helm > `spring.application.name`/build-info > Agent detectors > SDK defaults. Dual-mode таблица (agent vs consume).
- Тест-инвариант (если не покрыт): `otel_service_name_wins_over_platform` на `DefaultConfigProperties.createFromMap`.
- **Риск:** минимальный (поведение уже корректно по `ADR-resource-merge-precedence`).

### PR-5. Compatibility matrix — раздел extension SPI ✅
**Цель:** формализовать совместимость extension ↔ agent ↔ SDK autoconfigure SPI.
- Дополнить `docs/tracing/otel-compatibility-matrix.md` разделом: какие SPI-интерфейсы реализуем и из какого артефакта (`opentelemetry-sdk-extension-autoconfigure-spi` 1.62.0), что `ConfigurablePropagatorProvider`/`ConfigurableSamplerProvider` — stable, а `ConditionalResourceProvider` — internal/unstable (already noted в ADR), fallback-поведение.
- Таблица «extension version ↔ supported agent ↔ supported SDK SPI» (Фаза 15 §Version compatibility) — привязать к существующим pins (без новых Gradle properties — по `ADR-otel-direct-integration`).
- **Риск:** нет (docs).

### PR-6. SPI-загрузка/упаковка extension jar — проверки ✅
**Цель:** гарантировать, что 4 SPI реально видны Агенту из self-contained jar.
- `verifyAgentJarContents` / новый `verifyExtensionSpiRegistration` — проверить, что в `agentExtensionJar` присутствуют все 4 `META-INF/services/*` файла и классы.
- e2e (gated, Gentoo Docker) `PlatformSpiAgentSmokeTest`: subprocess с `-javaagent` + extension, `-Dotel.traces.sampler=platform`, `-Dotel.propagators=tracecontext,baggage,platform-trace-control` → проверить, что sampler/propagator/resource применились (через actuator/diagnostics или экспортированный span).
- **Риск (зафиксирован явно, по ревью архитектора):** теоретически OTel Agent может не вызвать `ServiceLoader` для `ConfigurableSamplerProvider`/`ConfigurablePropagatorProvider` из `ExtensionClassLoader`. **Оценка:** для Agent 2.x это штатно поддерживается — официальный `examples/extension` демонстрирует named `ConfigurableSamplerProvider`/`ConfigurablePropagatorProvider`, ссылаемые через `OTEL_PROPAGATORS`/`OTEL_TRACES_SAMPLER`. Поэтому риск — «подтвердить», а не «вероятно сломано».
  - **Триггер обнаружения:** `PlatformSpiAgentSmokeTest` падает немедленно, если named SPI не виден.
  - **Fallback-митигация (если SPI fail):** inline-customizer (`addSamplerCustomizer`/`addPropagatorCustomizer` + property-customizer) уже работает и остаётся единственным рабочим путём — named становится «недоступен», но рантайм-поведение сохраняется. Reflection-регистрация named SPI в кастомайзере — нежелательна (не предусмотрена API), к ней не прибегаем.
  - Перекликается с `ADR-classloader-visibility-spike-finding`.

### PR-7. ADR + документация ✅
- Написать `ADR-sdk-mode-detection.md`, `ADR-named-spi-sampler-propagator.md`, `ADR-config-precedence.md`.
- Обновить `SUPPORTED.md` (раздел «Agent mode: named SPI», precedence), `traceability.md` (Фаза 15 → классы/тесты), `CHANGELOG.md`.
- Обновить пример запуска (Фаза 15 §Рекомендуемая настройка) в runbook: `-Dotel.traces.sampler=platform -Dotel.propagators=tracecontext,baggage,platform-trace-control`.

### PR-8. Полная сборка + тесты обоих режимов ✅
- `./gradlew assemble test` (unit + ArchUnit) — ✅ BUILD SUCCESSFUL.
- e2e gated (Gentoo Docker `192.168.100.70:2375`): **29/29** green, incl. `PlatformSpiAgentSmokeTest` (2026-06-09, 4m 34s).
- `verifyOtelBomAlignment`, `verifyAgentJarContents`, `verifyExtensionSpiRegistration` — ✅.

---

## 8. Тест-матрица (оба режима — Фаза 15 «тестировать оба»)

> **Статус (2026-06-09):** все сценарии ниже покрыты и зелёные (unit/slice/ArchUnit/verify + e2e 29/29 на Gentoo Docker).

| Сценарий | Уровень | Режим |
|---|---|---|
| `otel.traces.sampler=platform` резолвится в CompositeSampler | integration | agent/autoconfigure |
| inline compose-over-existing сохранён при не-platform sampler | unit | agent |
| idempotent: нет двойной композиции sampler | unit | agent |
| `otel.propagators` дефолт включает platform-trace-control | integration | agent |
| явный `otel.propagators=...,platform-trace-control` — без дубля | integration | agent |
| `otel.propagators=none` — платформенный пропагатор не добавлен | integration | agent |
| propagator извлекает X-Trace-On/QA/Request-Id в Context | unit | оба |
| ResourceProvider: OTEL_SERVICE_NAME побеждает platform | unit | оба |
| sdk.mode resolver (agent/external/starter/disabled) | unit | оба |
| agent-режим не создаёт второй SDK | slice | consume |
| extension не импортирует Spring | ArchUnit | — |
| 4 SPI зарегистрированы в agentExtensionJar | verify-task | agent |
| e2e: агент + extension + named sampler/propagator | e2e (gated) | agent |
| одинаковая policy (force/QA/drop) в обоих путях | unit+e2e | оба |
| регрессы: ForceSampling, CompositeSampler*, PlatformTraceControlPropagator, baggage limits | unit/e2e | — |

---

## 9. Что НЕ делаем (anti-recommendations, обоснованно)

- ❌ **Programmatic `SdkTracerProvider` в starter'е** — противоречит `ADR-otel-direct-integration` (agent-first, запрет второго SDK, SDK-only Deferred P2). Риск двойной инструментации/дублей span'ов — anti-goal самой Фазы 15.
- ❌ **Custom `ConfigurableSpanExporterProvider`** — Фаза 15 §7 и `ADR-safe-span-exporter-v1`: стандартный путь OTLP→Collector + `SafeSpanExporter`-wrapper. Свой exporter — vendor lock-in без причины.
- ❌ **Дефолт `otel.traces.sampler=platform`** в v0.1.0 — сохраняем compose-over-existing как default (`ADR-sampler-compose`), named — явный opt-in (консервативно, не меняет рантайм существующих деплоев).
- ❌ **Bridge Spring `TracingProperties` → `-Dotel.*`** — отвергнут `ADR-dual-channel-properties` (overengineering; Agent конфигурируется до Spring context).
- ❌ **Новые Gradle version properties** для Agent — запрещено `ADR-otel-direct-integration` / matrix (`expectedJavaAgentVersion = openTelemetryInstrumentationBomVersion`).
- ❌ **Замена W3C** custom-пропагатором — `platform-trace-control` строго дополняет `tracecontext`/`baggage` (Фаза 15 §Propagator, `ADR-context-first-propagation`).

---

## 10. Риски и митигации

| Риск | Митигация |
|---|---|
| Двойная композиция sampler (named + customizer) | idempotency-guard через маркер `PlatformManagedSampler` (НЕ статический флаг) + unit-тесты, вкл. повторную сборку (§5.1, PR-1) |
| Дефолт propagator игнорируется при ENV-вводе | `addPropertiesCustomizer` вместо `addPropertiesSupplier` (ENV-aware) + тест `env_propagators_still_get_platform` (§5.2, PR-2) |
| Двойная регистрация propagator | дефолт-логика «add if absent» + удаление always-append (§5.2, PR-2) |
| Named SPI не виден Агенту из ExtensionClassLoader | подтверждение e2e `PlatformSpiAgentSmokeTest` (fail-fast) + inline-customizer как fallback (PR-6); для Agent 2.x штатно поддерживается |
| Classloader-видимость SPI в self-contained jar | `verifyExtensionSpiRegistration` + e2e (PR-6); ср. `ADR-classloader-visibility-spike-finding` |
| AGENT-режим ошибочно уходит в NoOp | фасад `DefaultPlatformTracing`→`GlobalOpenTelemetry` в agent/external; NoOp только для `disabled` + slice-тест (§5.3, PR-3) |
| `ConditionalResourceProvider` — internal SPI | уже под контролем (`ADR-resource-merge-precedence`): pinned BOM + degrade-fallback |
| Регресс рантайма у существующих деплоев | named SPI — opt-in; дефолты не меняют sampler; только propagators-дефолт расширяется (с guard) |
| Несовместимость версий agent/SPI | compatibility matrix (PR-5) + `verifyOtelBomAlignment` |

---

## 11. ADR-deliverables (итог)
1. **`ADR-sdk-mode-detection.md`** — `platform.tracing.sdk.mode`, детект, double-SDK guard, диагностика (NOT SDK creation).
2. **`ADR-named-spi-sampler-propagator.md`** — named `platform` sampler + `platform-trace-control` propagator; reconciliation с inline-customizer'ами; политика дефолтов; почему sampler-дефолт НЕ ставим.
3. **`ADR-config-precedence.md`** — единый precedence-контракт identity/policy (свод `ADR-resource-merge-precedence` + dual-mode).

## 12. Definition of Done

| Критерий | Статус |
|---|---|
| 4 SPI зарегистрированы и проверяются (`verifyExtensionSpiRegistration`) | ✅ |
| `otel.traces.sampler=platform` и `otel.propagators=...,platform-trace-control` работают (integration + e2e) | ✅ |
| Mode detection + double-SDK guard + actuator-видимость (`sdk`) | ✅ |
| Precedence + compatibility matrix задокументированы; 3 новых ADR | ✅ |
| ArchUnit «no Spring in extension» зелёный | ✅ |
| Полная сборка + unit/ArchUnit зелёные | ✅ |
| e2e gated (Gentoo Docker `192.168.100.70:2375`): **29/29** green, incl. `PlatformSpiAgentSmokeTest` | ✅ (2026-06-09) |
| `verifyOtelBomAlignment`, `verifyAgentJarContents`, `verifyExtensionSpiRegistration` | ✅ |
| Поведение существующих деплоев не изменилось (named — opt-in; sampler-дефолт прежний) | ✅ |

---

## 13. Отчёт о реализации (2026-06-09)

Фаза 15 выполнена полностью (PR-0 … PR-8). Реализация учитывала ревью архитектора: `addPropertiesCustomizer` вместо `addPropertiesSupplier` для propagator defaults; маркер `PlatformManagedSampler` вместо статического `AtomicBoolean`; `NoOpPlatformTracing` только для `DISABLED`.

### PR-0 — Reusable builders + ArchUnit

| Артефакт | Путь |
|---|---|
| `PlatformSamplerBuilder` | `platform-tracing-otel-extension/.../sampler/PlatformSamplerBuilder.java` |
| `PlatformTraceControlPropagatorBuilder` | `platform-tracing-otel-extension/.../propagation/PlatformTraceControlPropagatorBuilder.java` |
| ArchUnit «no Spring» | `platform-tracing-otel-extension/.../arch/ExtensionNoSpringDependencyArchTest.java` |

### PR-1 — Named sampler SPI `platform`

| Артефакт | Путь |
|---|---|
| `PlatformSamplerProvider` | `.../sampler/PlatformSamplerProvider.java` |
| `PlatformManagedSampler` (маркер) | `.../sampler/PlatformManagedSampler.java` |
| `PlatformManagedSamplers` (guard utils) | `.../sampler/PlatformManagedSamplers.java` |
| Idempotency в inline-customizer | `.../factory/PlatformSamplerFactory.java` (re-wire JMX без double-wrap) |
| `toString()` → `getDescription()` | `SafeSampler`, `CompositeSampler` |
| META-INF/services | `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider` |
| Тесты | `PlatformSamplerProviderTest`, `PlatformSpiAutoconfigureIntegrationTest` |

### PR-2 — Named propagator SPI `platform-trace-control`

| Артефакт | Путь |
|---|---|
| `PlatformTraceControlPropagatorProvider` | `.../propagation/PlatformTraceControlPropagatorProvider.java` |
| `PlatformPropagatorsDefaultsCustomizer` | `.../propagation/PlatformPropagatorsDefaultsCustomizer.java` |
| Wiring в customizer | `PlatformAutoConfigurationCustomizer` — `addPropertiesCustomizer(propagatorsDefaults)` |
| Удалён always-append | `PlatformPropagatorFactory` — только baggage-wrapper |
| META-INF/services | `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider` |
| Тесты | `PlatformTraceControlPropagatorProviderTest`, `PlatformPropagatorsDefaultsCustomizerTest`, `PlatformSpiAutoconfigureIntegrationTest`; регресс `BaggageFilteringSpikeTest` (assertion `>=2` propagators) |

### PR-3 — Mode detection + facade vs NoOp

| Артефакт | Путь |
|---|---|
| `SdkMode` enum | `platform-tracing-spring-boot-autoconfigure/.../support/SdkMode.java` |
| `SdkModeResolver` | `.../support/SdkModeResolver.java` |
| `SdkModeDiagnostics` | `.../support/SdkModeDiagnostics.java` |
| `TracingProperties.Sdk.mode` | `.../TracingProperties.java` |
| Wiring + лог | `TracingCoreAutoConfiguration` — bean `SdkModeDiagnostics`, `NoOp` только для `DISABLED` |
| Actuator `sdk` | `TracingActuatorEndpoint` + `TracingActuatorAutoConfiguration` |
| Agent-side диагностика | `ExtensionPropertyNames.SDK_MODE` + одноразовый лог в `PlatformAutoConfigurationCustomizer` |
| Тесты | `SdkModeResolverTest`, `SdkModeDetectionAutoConfigurationTest` |

### PR-4 — Precedence-контракт

| Артефакт | Путь |
|---|---|
| ADR | `docs/decisions/ADR-config-precedence.md` |
| Тест-инвариант (уже был) | `ResourceMergeIntegrationTest.otel_service_name_побеждает_platform` |

### PR-5 — Compatibility matrix

| Артефакт | Путь |
|---|---|
| Раздел Extension SPI | `docs/tracing/otel-compatibility-matrix.md` §Extension SPI surface |

### PR-6 — SPI packaging + e2e smoke

| Артефакт | Путь |
|---|---|
| Gradle verify-task | `platform-tracing-otel-extension/build.gradle` → `verifyExtensionSpiRegistration` |
| e2e smoke (gated) | `platform-tracing-e2e-tests/.../smoke/PlatformSpiAgentSmokeTest.java` |

### PR-7 — ADR + документация

| Документ | Путь |
|---|---|
| `ADR-named-spi-sampler-propagator.md` | `docs/decisions/` |
| `ADR-sdk-mode-detection.md` | `docs/decisions/` |
| `ADR-config-precedence.md` | `docs/decisions/` |
| `SUPPORTED.md` | раздел «Agent mode: named SPI и sdk.mode» |
| `traceability.md` | GAP-перечень Фазы 15 (P15-01 … P15-08) |
| `CHANGELOG.md` | секция «Фаза 15» в `[Unreleased]` |
| Runbook | `docs/runbook/actuator-tracing-diagnostics.md` — секция `sdk` |

### PR-8 — Сборка и регрессия

| Проверка | Результат |
|---|---|
| `assemble test` (unit + ArchUnit, `-DskipE2e=true`) | ✅ BUILD SUCCESSFUL |
| `verifyOtelBomAlignment` | ✅ |
| `verifyAgentJarContents` | ✅ |
| `verifyExtensionSpiRegistration` | ✅ |
| e2e Gentoo Docker (`DOCKER_HOST=tcp://192.168.100.70:2375`, `-PrunE2e`) | ✅ **29/29** (4m 34s, 2026-06-09) |

### Ключевые решения по ревью архитектора (зафиксированы в коде и ADR)

| Рекомендация | Принято? | Реализация |
|---|---|---|
| `addPropertiesCustomizer` вместо `addPropertiesSupplier` для propagator defaults | ✅ | `PlatformPropagatorsDefaultsCustomizer` |
| Idempotency sampler — маркер, не `AtomicBoolean` | ✅ | `PlatformManagedSampler` + `PlatformManagedSamplers` |
| `NoOp` только для `DISABLED`; agent/external → фасад | ✅ | `TracingCoreAutoConfiguration` + `SdkModeResolver` |
| Риск SPI visibility из ExtensionClassLoader | зафиксирован | e2e `PlatformSpiAgentSmokeTest` подтвердил: named SPI виден Agent 2.28.1 |

### Что сознательно не менялось (контракт pre-production)

- Дефолт `otel.traces.sampler=platform` **не** установлен (compose-over-existing остаётся default).
- Programmatic `SdkTracerProvider` в starter'е **не** добавлен (agent-first).
- `@Deprecated` **не** добавлялись.
- Кодировка файлов **не** менялась.
