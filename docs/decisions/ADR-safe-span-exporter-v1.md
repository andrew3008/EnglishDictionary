# ADR: SafeSpanExporter и сознательный отказ от SDK-side backpressure/priority (Фаза 10)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-08 |
| Версия | v1.x |
| Фаза | 10 — Export pipeline |
| Стек | OTel SDK BOM **1.62.0**, OTel instrumentation/Agent **2.28.1** |
| Связанные ADR | [ADR-drop-oldest-export-processor-v1.md](./ADR-drop-oldest-export-processor-v1.md), [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md), [ADR-processor-errors-metric.md](./ADR-processor-errors-metric.md) |
| Исследование | `Platform_Traces_Archive/Theory/Фазы/Фаза_10/BackPressure/Ответы на открытые вопросы.md` |

## Контекст

[ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md) в секции **Deferred (P2)** явно отложил:
`SafeSpanExporter` («after e2e/load validation») и `PrioritySpanProcessor` / `DropOldestQueueSpanProcessor`
(«только при подтверждённом gap стандартного BSP»). Настоящий ADR **закрывает** эти отложенные пункты:
вводит `SafeSpanExporter` и фиксирует осознанный отказ от SDK-side priority/backpressure.

Фаза 10 (export pipeline) требует: асинхронный экспорт, быстрый возврат управления,
локальную буферизацию, retry с backoff, обработку backpressure (429/503), bounded queue,
drop-oldest, приоритизацию (errors > slow > success) и fallback без исключений в бизнес-код.

Большинство пунктов уже закрыто: `PlatformDropOldestExportSpanProcessor` (bounded queue,
drop-oldest, worker, timeouts, forceFlush/shutdown), OTLP retry (SDK 1.59+), Collector
(`sending_queue`, `retry_on_failure`, `memory_limiter`, `tail_sampling`),
`ClassificationSpanProcessor` (атрибуты `platform.trace.priority`/`duration_class`).

Открытыми оставались два архитектурных вопроса:
1. Реализовывать ли SDK-side `BackpressureAwareExporter` + `CircuitBreaker` для 429/503?
2. Реализовывать ли SDK-side `PrioritySpanProcessor` (многоуровневые очереди, priority-aware drop)?

## Решение

### 1. Добавляем только `SafeSpanExporter` (Conservative)

Транспортный exporter (OTLP) оборачивается тонким `SafeSpanExporter`:

- любой `Throwable` из делегата → `CompletableResultCode.ofFailure()` (исключение не покидает pipeline);
- наблюдаемость отказов: счётчики `export_batches`/`export_batch_failures` (на вызов),
  `transport_dropped_spans`/`exported_spans` (на span), `flush_failures`, `shutdown_failures`,
  `last_export_duration_nanos`, `suppressed_spans_export_disabled`, `export_enabled`;
- никакого retry/буфера/блокировки внутри (антипаттерн thin transport adapter из Фазы 10).

**Семантика счётчиков (контракт наблюдаемости):** `export_batch_failures` инкрементируется на 1 за неуспешный
*вызов* (batch); `transport_dropped_spans` — на `spans.size()` за тот же batch. Это разделяет «один упавший сетевой
вызов» и «N потерянных span'ов».

Реализация: [`SafeSpanExporter`](../../platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/extension/exporter/SafeSpanExporter.java),
обёртывание в [`PlatformExportProcessorFactory.captureExporter`](../../platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/extension/factory/PlatformExportProcessorFactory.java)
(применяется и к платформенному процессору, и к stock BSP в режиме UPSTREAM).

### 2. SDK-side CircuitBreaker / BackpressureAwareExporter — НЕ реализуем

429/503 остаются на OTLP-клиенте (retry+backoff) и Collector (`memory_limiter` отдаёт 429,
`sending_queue` сглаживает всплески, `retry_on_failure` к backend'у).

Обоснование (из исследования):
- JVM уже защищён bounded-очередью (`maxQueueSize`) — переполнение деградирует в drop, не в OOM;
- OTLP-клиент уже делает bounded retry с backoff для transient-ошибок;
- сложный stress-monitor/circuit breaker в агенте — дополнительные потоки и state-логика,
  что противоречит ресурсным ограничениям (CPU < 3%, Mem < 10%): «telemetry must never compete
  with the application for resources»;
- источник истины delivery-policy централизованно правильнее держать на Collector.

### 3. SDK-side PrioritySpanProcessor — НЕ реализуем

Приоритизацию выполняет Collector `tail_sampling`, используя проставленные SDK атрибуты.
Обязательная политика `platform-high-priority` (`key=platform.trace.priority`, `values=[high]`)
добавлена в [`otel-collector-gateway-tail-sampling.yaml`](../../platform-tracing-collector-config/src/main/resources/platform-tracing/collector/otel-collector-gateway-tail-sampling.yaml).

Обоснование: SDK видит только локальные span'ы сервиса, а не всю распределённую трассу;
дроп «успешного» span'а в SDK ради «ошибочного» способен разорвать parent-child граф и породить
orphaned spans в backend'е. Tail-sampling в Collector имеет «вид сверху» на всю трассу — это
индустриальный стандарт.

## Наблюдаемость

В соответствии с [ADR-processor-errors-metric.md](./ADR-processor-errors-metric.md) метрики экспорта
реализованы как `LongAdder`/`AtomicLong` cumulative-счётчики и экспонируются через JMX + actuator,
а **не** через Micrometer `MeterRegistry`: export-компоненты живут в bootstrap-classloader'е Agent
extension, а `MeterRegistry` — в application-classloader (тот же classloader-барьер, что и для
`processorErrors`). Миграция на Micrometer — backlog v1.1 при решении classloader-моста.

`SafeSpanExporter` и `PlatformDropOldestExportSpanProcessor` экспонируются через JMX
`PlatformTracingControlMBean` (`getExportFailuresTotal`, `getExportTimeoutsTotal`,
`getExportDroppedOverflowTotal`, `getExportDroppedAfterShutdownTotal`,
`getExportQueueCapacity`/`getExportQueueSize`, `getSafeExporterMetrics`) и в `GET /actuator/tracing`
(секция `export`). Late-binding: export-компоненты создаются на стадии `addSpanProcessorCustomizer`
(позже регистрации MBean), поэтому MBean читает их лениво через холдеры `PlatformTracingJmxRegistrar`.

При отсутствии MBean (extension не подгружен или запрос пришёл до регистрации) actuator грациозно
отдаёт `export: { status: "not_ready" }` вместо HTTP 500.

## Последствия

**Плюсы:** минимальная кодовая база, отсутствие оверинжиниринга, соответствие ресурсным
ограничениям, совместимость с OpenTelemetry ecosystem, целостность распределённых трасс.

**Минусы / границы:** при длительной деградации Collector'а SDK не «умничает» — он просто роняет
span'ы по drop-oldest (это и есть желаемое поведение). Durable-буферизация и централизованная
delivery-policy — зона Collector/gateway, не SDK.

## Альтернативы (отклонены)

- **Full SDK-side circuit breaker (паттерн APM `CircuitBreaker`)** — отклонён: ресурсный overhead,
  рост сложности и риска багов в агенте.
- **SDK-side `PrioritySpanProcessor` с multi-queue** — отклонён: риск orphaned spans, дублирование
  ответственности Collector tail-sampling.

## Связанные артефакты

- `platform-tracing-otel-javaagent-extension/.../exporter/SafeSpanExporter.java`
- `platform-tracing-otel-javaagent-extension/.../factory/PlatformExportProcessorFactory.java`
- `platform-tracing-otel-javaagent-extension/.../factory/PlatformTracingJmxRegistrar.java`
- `platform-tracing-otel-javaagent-extension/.../jmx/PlatformTracingControl.java` / `PlatformTracingControlMBean.java`
- `platform-tracing-spring-boot-autoconfigure/.../actuator/TracingActuatorEndpoint.java` / `OtelEnvHintsBuilder.java`
- `platform-tracing-collector-config/.../otel-collector-gateway-tail-sampling.yaml` (политика `platform-high-priority`)
- Тесты: `SafeSpanExporterTest`, `PlatformExportProcessorFactorySafeWrapTest`, `PlatformTracingControlTest`, `TracingActuatorEndpointTest`
- [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md) (Deferred P2 — закрыт настоящим ADR)
- [ADR-drop-oldest-export-processor-v1.md](./ADR-drop-oldest-export-processor-v1.md)
- [ADR-processor-errors-metric.md](./ADR-processor-errors-metric.md) (конвенция метрик AtomicLong + JMX)
