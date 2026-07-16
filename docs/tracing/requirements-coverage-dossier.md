# Requirements Coverage Dossier: `Traces Requests.txt` → код + ADR

| Поле | Значение |
|------|----------|
| Назначение | Инженерное досье: каждый пункт `Traces Requests.txt` → конкретный класс/файл в кодовой базе + принятое решение (ADR) + доказывающий тест |
| Источник требований | `Platform_Traces_Archive\Traces Requests.txt` (единственный авторитетный документ) |
| Отличие от смежных документов | [traceability.md](traceability.md) — краткая матрица; [sre-requirements-coverage-review.md](sre-requirements-coverage-review.md) — границы и решения для SRE; **этот документ — code-grounded трассировка для разработчиков и ревью** |
| Каталог решений | [`docs/decisions`](../decisions) |

> Легенда статуса: **OK** — реализовано в Java; **OK (Collector)** — закрыто YAML-конфигом Collector'а (в поставке `platform-tracing-collector-config`); **OK / нормализовано** — требование выполнено через осознанную замену буквы (ADR); **GAP** — явно отложено с обоснованием.
>
> Пути классов даны от корня репозитория. Модули: `platform-tracing-api` (без Spring/OTel-SDK), `platform-tracing-core` (OTel-aware), `platform-tracing-otel-extension` (Agent extension, без Spring), `platform-tracing-spring-boot-autoconfigure`, `platform-tracing-collector-config` (YAML + JVM-тесты).

---

## §1. Генерация и управление Span'ами

### 1. API создания и завершения Span'ов (Open/Close, Defer)

| Аспект | Реализация | ADR | Проверка |
|--------|-----------|-----|----------|
| Программный фасад | `platform-tracing-api/.../api/TraceOperations.java` (интерфейс) + `platform-tracing-core/.../core/DefaultTraceOperations.java` (OTel-реализация) | [ADR-otel-direct-integration.md](../decisions/ADR-otel-direct-integration.md) | `DefaultTraceOperationsInSpanTest` |
| High-level `inSpan()` (Runnable/Supplier) | default-методы на `TraceOperations` поверх `startSpan()` + `SpanScope.close()` | — | `DefaultTraceOperationsInSpanTest` |
| Авто-инструментирование (без кода) | OTel Java Agent (Spring MVC/WebFlux/JDBC/Kafka) | [ADR-otel-direct-integration.md](../decisions/ADR-otel-direct-integration.md) | e2e smoke (`-PrunE2e`) |

Атрибуты/события/статус добавляются через typed span API (см. §1.2) и `SpanEnricher`.

### 1.1. Обязательные атрибуты span'а

Требуемые: `trace_id`, `span_id`, `service`, `version`, `environment`, `type`, `result`, `host`, `container`, `C_Group`.

| Атрибут | Реализация | Источник значения |
|---------|-----------|-------------------|
| `trace_id` / `span_id` | OTel SDK (генерация контекста) | автоматически |
| `service.name`, `service.version`, `deployment.environment.name`, `host.name`, `container.id` | `platform-tracing-otel-extension/.../resource/PlatformResourceProvider.java` (`container.id` — omit-if-unconfigured + procfs detector) | resource attrs |
| `platform.trace.type` (категория HTTP/DB/internal) | `platform-tracing-api/.../api/attributes/PlatformAttributes.java` + auto-instrumentation span kind | enrich/agent |
| `platform.trace.result` (success/failure + TIMEOUT/CANCELLED/…) | `platform-tracing-otel-extension/.../processor/EnrichingSpanProcessor.java` | enrich |

ADR: [ADR-platform-resource-override.md](../decisions/ADR-platform-resource-override.md), [ADR-resource-merge-precedence.md](../decisions/ADR-resource-merge-precedence.md). Проверка: `ResourceMergeIntegrationTest`, `PlatformResourceProviderTest`.

### 1.2. Типизированные span'ы (HTTP / DB / RPC / Exception)

| Тип | Покрытие | Реализация |
|-----|----------|-----------|
| HTTP / DB / RPC (auto) | OTel Agent instrumentation по semconv | `http.*` / `db.*` / `rpc.*` |
| Typed span API (вне покрытия Агента) | `httpServerSpan()`/`httpClientSpan()`/`databaseSpan()`/`rpcServerSpan()`/`kafkaProducerSpan()`/… фабрики на `TraceOperations` | builders в `platform-tracing-api/.../api/span/builder/` + `platform-tracing-core/.../span/*Impl` |
| Семантика типов (allowlist/required/forbidden) | `platform-tracing-api/.../api/semconv/CategoryContracts.java` | — |
| Exception span (sanitized) | `platform-tracing-core/.../core/exception/ExceptionRecorder.java` (`exception.message`/`stacktrace` off-by-default) | — |
| Санитизация URL/SQL | `platform-tracing-api/.../api/span/sanitize/UrlSanitizer.java`, `SqlSanitizer.java` | — |

ADR: [ADR-typed-span-api-semantic-layer.md](../decisions/ADR-typed-span-api-semantic-layer.md), [ADR-db-semconv-detection.md](../decisions/ADR-db-semconv-detection.md). Проверка: `EscapeHatchSpanBuilderTest`, `CategoryContractsTest`, `ExceptionRecorderTest`, `SanitizerTest`, e2e `DbSemconvAgentSmokeTest`.

Postgres/Kafka tracing — auto-instrumentation Агента; Kafka batch links — `RemoteSpanLink`/`startSpanWithLinks` (см. [links-kafka.md](links-kafka.md)).

### 3 (раздел §1). Передача контекста + флаги сэмплирования + `X-Trace-On`

| Аспект | Реализация | ADR |
|--------|-----------|-----|
| W3C TraceContext extract/inject | OTel Agent (default) + named SPI propagator `platform-trace-control`: `platform-tracing-otel-extension/.../propagation/InboundTraceControlPropagatorProvider.java` | [ADR-context-first-propagation.md](../decisions/ADR-context-first-propagation.md), [ADR-named-spi-sampler-propagator.md](../decisions/ADR-named-spi-sampler-propagator.md) |
| Уровень сэмплирования на сервис | `SamplerState.defaultRatio` (default 0.1), per-route ratios | [ADR-sampler-compose.md](../decisions/ADR-sampler-compose.md) |
| `X-Trace-On` → запись трейса | `ForceHeaderRule` в `CompositeSampler` → `platform.sampling.reason=force_header` | [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md) |
| Outbound-инъекция платформенных заголовков (secure-by-default DENY) | `platform-tracing-api/.../api/propagation/control/OutboundPropagationPolicy.java`, `TraceControlHeaderInjector.java` | [ADR-outbound-propagation.md](../decisions/ADR-outbound-propagation.md) |

Проверка: `CompositeSamplerTest`, `InboundTraceControlPropagatorProviderTest`, e2e `ForceSamplingAgentSmokeTest`, `RuntimeSamplingControlSmokeTest`.

---

## §2. Изоляция и отказоустойчивость

| # | Требование | Статус | Реализация (класс@путь) | ADR |
|---|-----------|--------|-------------------------|-----|
| 1 | Неблокирующее поведение; ошибки только логируются | OK | `platform-tracing-otel-extension/.../processor/PlatformCompositeSpanProcessor.java` (глотает исключения делегатов) + `.../exporter/SafeSpanExporter.java` (изоляция транспорта) | [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md), [ADR-composite-processor.md](../decisions/ADR-composite-processor.md) |
| 2 | Асинхронный экспорт | OK | OTel `BatchSpanProcessor` / `.../processor/PlatformDropOldestExportSpanProcessor.java` (фоновый worker) | [ADR-drop-oldest-export-processor-v1.md](../decisions/ADR-drop-oldest-export-processor-v1.md) |
| 3 | Таймаут операций (~100 мс) | OK / нормализовано | `TracingProperties.queue.exportTimeout` → `OTEL_BSP_EXPORT_TIMEOUT` (default выровнен 5000ms); буквальные 100мс не применяются | [ADR-performance-model.md](../decisions/ADR-performance-model.md) (Решение 2), [ADR-dual-channel-properties-v0.1.md](../decisions/ADR-dual-channel-properties-v0.1.md) |
| 4 | Автоматический fallback (тихий пропуск) | OK | drop-on-overflow + `PlatformCompositeSpanProcessor` + `SafeSpanExporter` | [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md) |
| — | CPU < 3% / Memory < 10% | **GAP (FAIL M5)** | evidence-модель; факт +48% CPU/+25% RSS — см. [architecture-committee-review.md](perf-results/2026-06-10_official/architecture-committee-review.md) | [ADR-performance-model.md](../decisions/ADR-performance-model.md) |
| 5 | Фиксированная очередь drop-oldest | OK (v1.x default) | `PlatformDropOldestExportSpanProcessor.java` (bounded `ArrayDeque`, drop-oldest, single-exporter); возврат к stock BSP: `…QUEUE_OVERFLOW_POLICY=UPSTREAM` | [ADR-drop-oldest-export-processor-v1.md](../decisions/ADR-drop-oldest-export-processor-v1.md), [ADR-bsp-overflow-policy-finding.md](../decisions/ADR-bsp-overflow-policy-finding.md) |
| 6 | Принудительный сброс при переполнении | OK | bounded queue + rate-limited warning | — |
| 7 | Соответствие OTel semconv | OK | `.../processor/ValidatingSpanProcessor.java` (runtime) + semconv-lint | [ADR-semconv-governance-weaver.md](../decisions/ADR-semconv-governance-weaver.md) |
| 8 | Валидация обязательных полей (`service.name`) | OK + OK (Collector) | `ValidatingSpanProcessor.java` (SDK) + `transform/platform-semconv-backstop` в gateway YAML | [ADR-semconv-validation-modes.md](../decisions/ADR-semconv-validation-modes.md) |

Проверка: `SafeSpanExporterTest`, `PlatformCompositeSpanProcessorTest`, `PlatformDropOldestExportSpanProcessor{OverflowPolicy,Lifecycle,BuilderValidation}Test`, `ValidatingSpanProcessorTest`, e2e `CollectorUnavailableResilienceTest`, `BspDropOldestSafetyAgentSmokeTest`.

---

## §2.1–§2.5. Контроль объёма данных в span'е

| # | Требование | Default | Статус | Реализация | ADR/проверка |
|---|-----------|---------|--------|-----------|--------------|
| 2.1 | Лимит атрибутов | 50 | OK | `platform-tracing-spring-boot-autoconfigure/.../TracingProperties.java` `Limits.maxAttributes` → `OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` | `SpanLimitsBenchmark` |
| 2.2 | Лимит длины значения | 1000 | OK | `TracingProperties.Limits.maxAttributeValueLength` → `OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT` | — |
| 2.3 | Лимит событий | 10 | OK | `TracingProperties.Limits.maxEvents` → `OTEL_SPAN_EVENT_COUNT_LIMIT` | — |
| 2.4 | Маскирование PII (логины/пароли/email/токены/ПДн), расширяемые правила | enabled | OK (2 линии) | 1-я: `platform-tracing-otel-extension/.../processor/ScrubbingSpanProcessor.java` + `.../scrubbing/BuiltInSpanAttributeScrubbingRules.java` (password/jwt/email/pan/phone) + SPI; 2-я: `redaction/platform-second-line` в gateway YAML | [ADR-scrubbing-cost.md](../decisions/ADR-scrubbing-cost.md); `ScrubbingEngineBenchmark`, `ScrubbingPerRuleBenchmark` |
| 2.5.1 | Span timeout 30s → status error + флаг timeout | 30s | OK | `platform-tracing-otel-extension/.../processor/SpanWatchdogProcessor.java` → `platform.trace.result=timeout` | `SpanWatchdogProcessorTest` |
| 2.5.2 | Trace timeout 60s → пометка аномального | 60s | OK | `SpanWatchdogProcessor.java` (traceTimeout) | `SpanWatchdogProcessorTest` |

Маскирование событий (не только атрибутов) закрыто `ExceptionRecorder` + `ExceptionMessagePolicy` (off-by-default message/stacktrace).

---

## §3. Экспорт и доставка span'ов

| # | Требование | Статус | Реализация | ADR |
|---|-----------|--------|-----------|-----|
| 3.1 | Быстрый ответ при отправке | OK | async export (worker-поток) + `SafeSpanExporter.java`; бизнес-поток не блокируется | [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md) |
| 3.2 | Локальная буферизация | OK (SDK) / durable → Collector | bounded in-memory очередь (`TracingProperties.Queue.maxSize=2048`); durable — зона Collector | [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md) |
| 3.3 | Retry с exponential backoff | OK (Collector) | OTLP-клиент (`retry.disabled=false`) + Collector `retry_on_failure`; **SDK-обёртка retry не дублирует** | [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md) |
| 3.4 | Backpressure (429/503) | OK (Collector) | Collector `sending_queue` + `memory_limiter`; SDK-side circuit breaker сознательно не реализован (ресурсные бюджеты), `SafeSpanExporter` изолирует+считает | [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md) |
| 3.5 | Приоритизация errors > slow > success | OK (Collector) | SDK: `.../processor/ClassificationSpanProcessor.java` проставляет `platform.trace.priority`/`duration_class`; retention: `tail_sampling` policies в `otel-collector-gateway-tail-sampling.yaml` (errors-always / slow-traces / platform-high-priority). SDK-side priority eviction — **GAP-PRIORITY-EVICTION** | [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md), [ADR-performance-model.md](../decisions/ADR-performance-model.md) (Решение 4) |

Проверка: `CollectorPolicyContractTest`, `CollectorProductionPolicyE2ETest`, `ClassificationSpanProcessorTest`.

---

## §4. Конфигурируемость

| # | Требование | Статус | Реализация | ADR |
|---|-----------|--------|-----------|-----|
| 4 | Все лимиты/таймауты/ratio/очереди через конфиг (без кода) | OK | `TracingProperties` (`platform.tracing.*`) + extension SPI дефолты (`PlatformTracingDefaultsProvider`); `GET /actuator/tracing` (`otelEffective` + `otelEnvHints`) | [ADR-config-precedence.md](../decisions/ADR-config-precedence.md), [ADR-dual-channel-properties-v0.1.md](../decisions/ADR-dual-channel-properties-v0.1.md) |
| 4 | Динамическое переключение «на лету» (log level / ratio / on-off) | OK | runtime policy: JMX `PlatformTracingControl` + `platform-tracing-spring-boot-autoconfigure/.../actuator/TracingActuatorEndpoint.java`; снимок `SamplerState` (CAS + LKG) | [ADR-runtime-config-policy-vs-topology.md](../decisions/ADR-runtime-config-policy-vs-topology.md), [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md) |

Граница policy (runtime-mutable) vs topology (startup-only) формализована в [ADR-runtime-config-policy-vs-topology.md](../decisions/ADR-runtime-config-policy-vs-topology.md): endpoint/limits/processor-chain меняются только через rolling update. Проверка: `PlatformTracingControlTest`, `SamplerStateHolderTest`, `SamplerRuntimeUpdateConcurrencyTest`, e2e `RuntimeSamplingControlSmokeTest`.

---

## «Параметры для управления» (раздел в конце `Traces Requests.txt`)

Цепочка решений head-сэмплера: `KillSwitch → HardDrop → ForceHeader → QaTrace → ParentDecision → RouteRatio → DefaultRatio` (`platform-tracing-otel-extension/.../sampler/CompositeSampler.java`). Порядок и приоритеты ратифицированы [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md).

| Требование заказчика | Статус | Реализация | ADR |
|----------------------|--------|-----------|-----|
| % сэмплирования трейса | OK | head ratio в `SamplerState` + `DefaultRatioRule`/`RouteRatioRule` | [ADR-sampler-compose.md](../decisions/ADR-sampler-compose.md) |
| Ошибки **всегда** сэмплируются (желательно на Collector'е) | OK (Collector) | `tail_sampling.errors-always-sample` (status_code=ERROR) — **по явному пожеланию** | [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md) |
| Общие запросы без сэмплирования / по % | OK | `DefaultRatioRule` (детерминированный `traceIdRatioBased`) | [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md) (§2.3) |
| Кастомный header → 100% запись (для авто-тестов) | OK | `ForceHeaderRule` (`X-Trace-On`), значения настраиваются `forceRecordValues` | [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md) |
| QA-ручка возвращает Request ID | OK | `QaTraceRule` (`X-QA-Trace`); response-заголовок `X-Request-Id`; `platform-tracing-core/.../propagation/RequestIdSupport.java` (reject-and-regenerate, CWE-113) | [ADR-request-id-correlation-id.md](../decisions/ADR-request-id-correlation-id.md), [ADR-response-headers.md](../decisions/ADR-response-headers.md) |

Контракт значений `platform.sampling.reason` (`platform-tracing-api/.../api/attributes/PlatformSamplingReasons.java`) машинно охраняется `CollectorPolicyContractTest` — Collector-политики не могут ссылаться на несуществующие причины.

---

## Сводка осознанных GAP

| GAP | Требование | Решение | ADR |
|-----|-----------|---------|-----|
| CPU<3%/Mem<10% (M5 FAIL) | §2 ресурсные бюджеты | optimization program / waiver / scope — решение комитета+SRE; устойчивость и отсутствие утечек подтверждены | [ADR-performance-model.md](../decisions/ADR-performance-model.md) |
| GAP-EXPORT-TIMEOUT-100MS | §3 таймаут ~100 мс | нормализовано: отзывчивость закрыта async-экспортом; 100мс на сетевой OTLP → ложные таймауты | [ADR-performance-model.md](../decisions/ADR-performance-model.md) |
| GAP-PRIORITY-EVICTION | §3.5 priority eviction из очереди | retention закрыт на tail-уровне; SDK priority-queue → риск orphaned spans; backlog | [ADR-performance-model.md](../decisions/ADR-performance-model.md) (Решение 4) |

---

## Карта модулей (где что лежит)

| Модуль | Роль | Ключевые классы из досье |
|--------|------|--------------------------|
| `platform-tracing-api` | контракты без Spring/OTel-SDK | `TraceOperations`, `PlatformAttributes`, `PlatformSamplingReasons`, `CategoryContracts`, `OutboundPropagationPolicy`, `UrlSanitizer`/`SqlSanitizer` |
| `platform-tracing-core` | OTel-aware реализации и pure core utilities | `DefaultTraceOperations`, `ExceptionRecorder`, `AttributePolicy`, `RequestIdSupport` |
| `platform-tracing-otel-extension` | Agent extension (без Spring) | `CompositeSampler`/`SamplerState`, `*SpanProcessor` (Scrubbing/Validating/Enriching/Classification/Watchdog/Composite/DropOldest), `SafeSpanExporter`, `PlatformResourceProvider`, SPI providers, JMX `PlatformTracingControl` |
| `platform-tracing-spring-boot-autoconfigure` | Spring-интеграция | `TracingProperties`, `TracingActuatorEndpoint`, `SamplingControlClient` |
| `platform-tracing-collector-config` | versioned YAML + JVM-контракт-тесты | `otel-collector-gateway-tail-sampling.yaml`, `CollectorPolicyContractTest` |

---

## Сопровождение

Документ обновляется вместе с `Traces Requests.txt`: новое требование → новая строка с классом@путём + ADR + тестом, либо явный GAP. Сквозная сверка статусов — с [traceability.md](traceability.md) и [requirements-traceability.md](requirements-traceability.md).
