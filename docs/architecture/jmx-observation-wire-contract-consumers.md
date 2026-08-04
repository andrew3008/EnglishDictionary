# Потребители JMX Observation Wire-контракта

| Поле | Значение |
|------|----------|
| Версия | 1.0 |
| Дата | 2026-08-04 |
| Статус | **Inventory для архитектурного комитета** |
| ADR | [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md) |
| Связанные briefs | [diagnostics value](./jmx-wire-contract-diagnostics-value-for-committee.md), [diagnostics examples](../runbook/jmx-wire-contract-diagnostics-examples.md) |

---

## 1. Назначение документа

Зафиксировать **полный перечень потребителей** wire-контракта шести domain-level JMX observation (`JmxObservationWireContract`) с разделением:

1. кто **кодирует** по контракту (producer);
2. кто **напрямую читает константы** контракта (direct consumer);
3. кто потребляет **decode-результат** observation (indirect / downstream consumer);
4. кто использует контракт только в **verification/CI**;
5. кто контракт **не** использует (явные non-consumers).

Документ основан на фактических call site репозитория, не на намерениях roadmap.

---

## 2. Определение «потребитель»

Wire-контракт — две зеркальные копии констант:

| Роль | Модуль | Тип |
|------|--------|-----|
| Producer copy | `platform-tracing-otel-javaagent-extension` | `…jmx.observation.JmxObservationWireContract` |
| Consumer copy | `platform-tracing-spring-boot-autoconfigure` | `…diagnostics.jmx.wire.JmxObservationWireContract` |

Общего Java-типа нет (classloader isolation).

| Термин | Значение в этом документе |
|--------|---------------------------|
| **Producer** | Код, который **строит** `CompositeData`/`CompositeType` по константам контракта |
| **Direct consumer** | Код, который **импортирует и читает** поля `JmxObservationWireContract` при decode/validate |
| **Downstream consumer** | Код, который **не** импортирует контракт, но читает DTO/`Optional` observation, полученные только через decode path контракта |
| **Verification consumer** | Тесты/Gradle gates, сверяющие константы/golden/parity, без production runtime path |

«Потребитель wire-контракта» для комитета = direct + downstream (+ verification как отдельный слой). Producer указан для полноты канала.

---

## 3. Сквозной канал (один раз)

```text
[Agent extension CL]
  Platform*Control  ──encode──► CompositeData (OpenType)
        ▲                         │
        │ константы               │ JMX attribute *Observation
        │ JmxObservationWireContract (agent copy)
                                  ▼
                         Platform MBeanServer
                                  │
[App / Spring CL]                 │
  PlatformTracingJmxClient        │
    └─ *JmxView.decode            │
         └─ JmxObservationCodec ◄─┘ константы consumer copy
              │
              ├─► *Observation / *Values DTO
              │       ├─ TracingStatusAssembler → /tracingStatus
              │       ├─ Micrometer *MetricsBinder
              │       └─ Domain health gauges
              └─► JmxContractViolation → jmxContractDiagnostics
```

Контракт используется **на границе encode/decode**. Downstream работает уже с DTO и diagnostics registry.

---

## 4. Producer-слой (encode) — не consumer, но обязательная сторона контракта

Все ниже — **единственные production encode call site** agent-копии контракта.

| Producer | Observation attribute | Используемые части контракта |
|----------|----------------------|------------------------------|
| `PlatformSamplingControl` | `SamplingObservation` | `SCHEMA_VERSION`, frame items (через `JmxOpenTypeSupport`), `SAMPLING_*`, `SAMPLING_COMPOSITE_TYPE_NAME` |
| `PlatformScrubbingControl` | `ScrubbingObservation` | frame + `ITEM_OWNER_*` + `SCRUBBING_*` |
| `PlatformValidationControl` | `ValidationObservation` | frame + `ITEM_OWNER_*` + `VALIDATION_*` |
| `PlatformExportControl` | `ExportObservation` | frame + nested `EXPORT_PROCESSOR_*` / `EXPORT_EXPORTER_*` + `EXPORT_*` |
| `PlatformProcessorMetricsControl` | `ProcessorMetricsObservation` | frame + nested watchdog/composite/metrics `PROCESSOR_METRICS_*` |
| `PlatformDiagnosticsControl` | `DiagnosticsObservation` | frame + `DIAGNOSTICS_*` |
| `JmxOpenTypeSupport` | общий frame builder | `ITEM_SCHEMA_VERSION`, `ITEM_CAPTURE_*`, `ITEM_AGENT_INSTANCE_ID` |

**Не producer wire-контракта:** OTLP exporters, Spring `Environment`, Actuator mutation API, control-protocol digests — они не кодируют эти шесть observation.

---

## 5. Direct consumers (читают константы контракта в production)

### 5.1 Decode infrastructure

| Компонент | Модуль | Что потребляет из контракта |
|-----------|--------|-----------------------------|
| `JmxObservationCodec` | autoconfigure | `SCHEMA_VERSION`, `ITEM_SCHEMA_VERSION`, `ITEM_OWNER_PRESENT`, `ITEM_OWNER_GENERATION`; gate `requireCompositeTypeName` / `requireSchemaVersion`; item accessors вызываются с именами из views |
| `SamplingJmxView` | autoconfigure | `SAMPLING_COMPOSITE_TYPE_NAME`, все `SAMPLING_ITEM_*`, `ITEM_AGENT_INSTANCE_ID` |
| `ScrubbingJmxView` | autoconfigure | `SCRUBBING_COMPOSITE_TYPE_NAME`, `SCRUBBING_ITEM_*`, owner frame items |
| `ValidationJmxView` | autoconfigure | `VALIDATION_COMPOSITE_TYPE_NAME`, `VALIDATION_ITEM_*`, owner frame items |
| `ExportJmxView` | autoconfigure | `EXPORT_COMPOSITE_TYPE_NAME`, nested `EXPORT_*_VALUES_TYPE_NAME`, `EXPORT_ITEM_*`, processor/exporter item names |
| `ProcessorMetricsJmxView` | autoconfigure | `PROCESSOR_METRICS_COMPOSITE_TYPE_NAME`, nested `*_VALUES_TYPE_NAME`, item names watchdog/composite/metrics |
| `DiagnosticsJmxView` | autoconfigure | `DIAGNOSTICS_COMPOSITE_TYPE_NAME`, `DIAGNOSTICS_ITEM_*` |

Это **единственный production decode path**. Других decode-клиентов OpenType observation в production-коде нет.

### 5.2 Фасад (не читает константы, но обязателен для всех downstream)

| Компонент | Роль |
|-----------|------|
| `PlatformTracingJmxClient` | Композиция шести `*JmxView` + `registrationSnapshot()` + `jmxContractDiagnostics()` |
| `JmxReadExecutor` / `JmxReadStateRegistry` | Transport + запись `JmxDiagnosticSnapshot` при violation (в т.ч. `COMPOSITE_TYPE_NAME_MISMATCH`) |

Фасад **не** импортирует `JmxObservationWireContract`, но является единственной точкой входа downstream.

### 5.3 Classification lists / allowlist — **не** runtime direct consumers

Следующие поля контракта **объявлены в обеих копиях**, но в production main-коде autoconfigure/agent **не читаются** binders/views:

- `SAMPLING_DECISION_COUNTS_CLASSIFICATION`
- `SAMPLING_ROUTE_RATIOS_CLASSIFICATION`
- `SCRUBBING_METRICS_CLASSIFICATION`
- `EXPORT_EXPORTER_METRICS_CLASSIFICATION`
- `DIAGNOSTICS_CONFIG_RELOAD_CLASSIFICATION`
- `DIAGNOSTICS_SAFE_WRAPPER_CLASSIFICATION`
- `PROCESSOR_ERRORS_BY_NAME_ALLOWLIST`
- `COMPOSITE_TYPE_NAMES_BY_DOMAIN` (используется в unit-тестах type-name gate)

Runtime Micrometer key-space зеркалит их через **отдельные package-private enum** (`SamplerDecisionKey`, `ScrubbingMetricKey`, …). Связь enum↔classification защищена parity-тестами, не runtime lookup `List<DynamicMapKey>`.

Для комитета: classification — **нормативная спецификация + CI sync**, не hot-path consumer.

---

## 6. Downstream consumers (production) — потребляют decode DTO

Все зависят от успешного decode path §5. При type-name/schema violation observation отсутствует → эти потребители видят empty/`NaN`/нули по своим правилам.

### 6.1 Status / Actuator read-model

| Потребитель | Вход | Выход |
|-------------|------|-------|
| `TracingStatusAssembler` | все шесть `*.observation()`, `registrationSnapshot()`, `jmxContractDiagnostics()` | `TracingStatusSnapshot` |
| `TracingStatusEndpoint` | assembler | HTTP/Actuator `tracingStatus` |

Секции `runtime`, заполняемые из wire-decode:

| Секция / поле | Источник observation |
|---------------|----------------------|
| `runtime.domainMBeansAvailable` | registration snapshot (рядом с wire, не item-decode) |
| `runtime.sampling.*` (+ `decisions`) | `SamplingObservation` (+ `invalidConfigCount` из Diagnostics) |
| `runtime.validation.*` | `ValidationObservation` |
| `runtime.scrubbing.*` | `ScrubbingObservation` |
| `runtime.export.*` | `ExportObservation` |
| `runtime.processorErrorsTotal` / `processorErrorsByName` | `ProcessorMetricsObservation.composite` |
| `runtime.configurationReload` | `DiagnosticsObservation.configReloadMetrics` |
| `jmxContractDiagnostics` | registry отказов decode/transport (вкл. type-name gate) |

Wiring: `TracingStatusAutoConfiguration` ← bean `PlatformTracingJmxClient` из `TracingRuntimeDiagnosticsAutoConfiguration`.

### 6.2 Micrometer binders (domain metrics)

Wiring: `TracingMetricsAutoConfiguration` (все `@ConditionalOnBean(PlatformTracingJmxClient)`).

| Binder | Domain observation | Основные metric names |
|--------|--------------------|-----------------------|
| `PlatformTracingSamplerMetricsBinder` | Sampling (+ Diagnostics `invalidConfigCount`) | `platform.tracing.sampler.decisions`, `platform.tracing.sampling.invalid_config`, reset/anomaly series |
| `PlatformTracingScrubbingMetricsBinder` | Scrubbing | `platform.tracing.scrubbing.count`, reset/anomaly |
| `PlatformTracingExportMetricsBinder` | Export | `platform.tracing.export.processor.count`, `platform.tracing.export.exporter.count`, reset/anomaly |
| `PlatformTracingProcessorMetricsBinder` | ProcessorMetrics | `platform.tracing.processor.watchdog.count`, `…composite.errors_*`, `…metrics.count`, reset/anomaly |
| `PlatformTracingConfigMetricsBinder` | Diagnostics (`configReloadMetrics`) | `platform.tracing.config.updates`, `…last_update_epoch_ms` |
| `PlatformTracingSafeWrapperMetricsBinder` | Diagnostics (`safeWrapperMetrics`) | `platform.tracing.safe_wrapper.*` |
| `PlatformTracingJmxDomainHealthMetricsBinder` | все домены: registration + observation presence/freshness/owner | `platform.tracing.jmx.domain.registered/readable/last_success_age_seconds`, `…owner.present` |

Вспомогательные runtime-типы на этом пути (не контракт, но часть consumer stack):

- `CoalescingJmxObservationSource` — coalescing diagnostics observation для нескольких binders;
- `MonotonicJmxCounterGroupBridge` — monotonic накопление cumulative карт/скаляров.

### 6.3 Внешние (за пределами модулей контракта)

| Потребитель | Характер |
|-------------|----------|
| Prometheus / Micrometer registry приложения | scrape metric names из §6.2 |
| Операторы / SRE / alerting | `tracingStatus` + метрики |
| Любой кастомный Spring bean | **только** если инжектит `PlatformTracingJmxClient` (публичный фасад); прямого доступа к `JmxObservationWireContract` в app-коде нет |

На момент inventory **нет** других production-модулей репозитория (`webmvc`/`webflux` starters, `otel` core, samples), которые напрямую импортируют consumer-копию контракта или сами decode'ят observation в обход `PlatformTracingJmxClient`.

---

## 7. Матрица «домен → кто читает»

| Domain observation | Direct decode | Status | Micrometer domain binder | Health binder |
|--------------------|---------------|--------|--------------------------|---------------|
| Sampling | `SamplingJmxView` | `runtime.sampling` | Sampler decisions (+ invalid_config из Diagnostics) | sampling / sampler owner |
| Scrubbing | `ScrubbingJmxView` | `runtime.scrubbing` | Scrubbing counts | scrubbing owner |
| Validation | `ValidationJmxView` | `runtime.validation` | — (нет отдельного cumulative binder) | validation owner |
| Export | `ExportJmxView` | `runtime.export` | Export processor + exporter | export processor/exporter owners |
| ProcessorMetrics | `ProcessorMetricsJmxView` | processor error fields | Watchdog + composite errors + metrics drops | three owners |
| Diagnostics | `DiagnosticsJmxView` | configurationReload (+ parts of sampling) | Config + SafeWrapper (+ sampler invalid_config) | diagnostics domain |

---

## 8. Verification / CI consumers

Не production runtime, но архитектурно значимы как **потребители нормативности** контракта.

| Consumer | Что проверяет |
|----------|---------------|
| `JmxObservationWireContractIndependentVerifyTest` (agent + autoconfigure) | scalar constants ↔ golden resource |
| `jmxWireGoldenFilesIdenticalCheck` / `jmxWireGoldenParityVerify` | byte-identity двух golden |
| `JmxObservationCodecTypeNameTest` | gate kind на 11 typeName |
| `JmxViewTypeNameMismatchWiringTest` | gate реально вызван из каждого `*JmxView` |
| `SamplingDecisionCountsClassificationParityTest` (agent) | producer keys ↔ agent classification |
| `SamplerDecisionKeyClassificationParityTest` | autoconfigure classification ↔ `SamplerDecisionKey` |
| `ScrubbingMetricKeyClassificationParityTest` | cumulative scrubbing enum ↔ classification |
| `ExportExporterMetricKeyClassificationParityTest` | cumulative exporter enum ↔ classification |
| `ProcessorCompositeErrorKeyAllowlistParityTest` | named errors ↔ allowlist |
| Binder unit/functional tests | decode→Micrometer поведение |
| `domainObservationE2eTest` | cross-CL encode(agent)→decode(autoconfigure) |
| Smoke mains (`KafkaAgentParitySmokeMain`, др.) | ручной/smoke probe observation attributes |

---

## 9. Явные non-consumers

| Компонент / слой | Почему не consumer wire-контракта |
|------------------|-----------------------------------|
| OTLP / Collector export path | Другой протокол и схема |
| Spring `TracingProperties` / Config Server desired state | Конфигурационный канал, не JMX observation |
| Control-protocol / candidate digests | Отдельный control wire |
| `TracingStatusSnapshot.CURRENT_SCHEMA_VERSION` | Схема JSON status endpoint, не JMX observation schema |
| Agent sampler/processor business logic (`CompositeSampler`, scrubbing engine) | Пишет свои counters; wire-контракт использует только JMX control при encode snapshot |
| Binders' closed enums | Зеркалят classification, но **не** читают `JmxObservationWireContract` в runtime |
| Historical docs `docs/refactoring/jmx-client-domain-split/*` | Описание, не executable consumer |

---

## 10. Выводы для архитектурного комитета

1. **Wire-контракт — не «diagnostics-only».** Его production consumers — status read-model **и** Micrometer operational metrics **и** domain health gauges.
2. **Узкое горло decode:** единственный production consumer констант — `JmxObservationCodec` + шесть `*JmxView`. Все остальное — downstream от `PlatformTracingJmxClient`.
3. **Classification lists** — нормативная спецификация + CI; не hot-path runtime consumers.
4. **Расширение потребителей** должно идти через `PlatformTracingJmxClient` / decode DTO, а не через прямой импорт контракта из app-кода или второй decode path.
5. **Несовместимость agent↔starter** ломает сразу весь fan-out §6 (status + metrics + health), поэтому type-name/schema diagnostics — compatibility alarm для всего operational read-model, не только для секции `jmxContractDiagnostics`.

---

## 11. Связанные материалы

- Wire ADR: [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md)
- Absent-owner: [ADR-jmx-absent-owner-encoding](../decisions/ADR-jmx-absent-owner-encoding.md)
- Status runbook: [tracing-status-diagnostics](../runbook/tracing-status-diagnostics.md)
- Diagnostics value brief: [jmx-wire-contract-diagnostics-value-for-committee](./jmx-wire-contract-diagnostics-value-for-committee.md)
- Diagnostics examples: [jmx-wire-contract-diagnostics-examples](../runbook/jmx-wire-contract-diagnostics-examples.md)
