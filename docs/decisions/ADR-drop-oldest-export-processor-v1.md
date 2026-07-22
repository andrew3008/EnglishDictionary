# ADR: Custom DROP_OLDEST export SpanProcessor (v1.x, opt-in)

| Поле | Значение |
|------|----------|
| Статус | **Accepted** |
| Дата | 2026-05-25 |
| Версия | v1.x (opt-in) |
| Связанный finding | [ADR-bsp-overflow-policy-finding.md](ADR-bsp-overflow-policy-finding.md) (v0.1.0 — stock BSP = drop-new) |
| SPI spike | [`BspReplacementSpikeTest`](../../platform-tracing-otel-javaagent-extension/src/test/java/space/br1440/platform/tracing/otel/extension/spike/BspReplacementSpikeTest.java) |
| Plan | `bsp_drop_oldest_v1.x_d5717054.plan.md` |

## Контекст

Платформа в v0.1.0 заявляет `TracingProperties.Queue.OverflowPolicy.DROP_OLDEST` как desired policy,
но стандартный `BatchSpanProcessor` SDK 1.61.0 фактически реализует **drop-new** (подтверждено
[`BatchSpanProcessorOverflowPolicyProbeTest`](../../platform-tracing-otel/src/test/java/space/br1440/platform/tracing/core/bsp/BatchSpanProcessorOverflowPolicyProbeTest.java)).
Это расхождение зафиксировано в [ADR-bsp-overflow-policy-finding.md](ADR-bsp-overflow-policy-finding.md)
с переформулировкой требования до `bounded-queue-with-drop` для v0.1.0.

v1.x вводит **гарантированный DROP_OLDEST** через собственный export-процессор, заменяющий стоковый
`BatchSpanProcessor` в OTel pipeline.

## Решение

### Opt-in only

| Версия | Default | DROP_OLDEST |
|--------|---------|-------------|
| v0.1.0 | Stock BSP (drop-new) | Aspirational, не enforced |
| **v1.x default** | **Stock BSP (upstream)** | Не активируется |
| **v1.x opt-in** | Stock BSP | Активируется по `platform.tracing.queue.overflow-policy=DROP_OLDEST` (env `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=DROP_OLDEST`) |
| v2.x+ | Отдельное решение по production-метрикам |  |

**Board-ready формулировка:**

> We choose opt-in for custom DROP_OLDEST in v1.x. The default remains upstream
> BatchSpanProcessor behavior to avoid silently changing overflow semantics under load.
> Guaranteed DROP_OLDEST becomes an explicit platform mode, enabled only by configuration
> and documented as a behavior-changing feature.

**Почему не default:** Spring property `DROP_OLDEST` в v0.1.0 не совпадает с runtime (stock BSP = drop-new).
Включение custom processor по умолчанию = не «сохранение поведения», а **исправление semantics
с изменением production behavior под overload** (какие spans сохраняются, профиль CPU/памяти,
traces при инциденте, ожидания SRE).

### Замена через autoconfigure SPI (подтверждено spike)

В `PlatformAutoConfigurationCustomizer.customize`:

1. `addSpanExporterCustomizer` — захват `SpanExporter` и подсчёт количества exporter'ов
2. `addSpanProcessorCustomizer` — замена `BatchSpanProcessor` на платформенный процессор только при
   `policy=DROP_OLDEST` AND single exporter AND stock BSP

Псевдокод (полная реализация в `PlatformAutoConfigurationCustomizer`):

```java
customizer.addSpanExporterCustomizer((exporter, cfg) -> {
    if (exporterCount.incrementAndGet() == 1) capturedExporter.set(exporter);
    else multiExporterDetected.set(true);
    return exporter;
});
customizer.addSpanProcessorCustomizer((processor, cfg) -> {
    if (!isExplicitDropOldest(cfg)) return processor;
    if (multiExporterDetected.get() || capturedExporter.get() == null) {
        log.warn("Multi/null exporter → fallback UPSTREAM"); return processor;
    }
    if (!(processor instanceof BatchSpanProcessor)) {
        log.warn("Non-BSP processor → passthrough"); return processor;
    }
    // Явно закрываем стоковый BSP, чтобы его worker не оставался жить.
    processor.shutdown().join(5, TimeUnit.SECONDS);
    log.info("Platform DROP_OLDEST export processor enabled (behavior-changing opt-in)");
    return PlatformDropOldestExportSpanProcessor.builder(capturedExporter.get())
            .readBspConfigFrom(cfg).build();
});
```

## SPI spike: подтверждённые факты SDK 1.61.0

Spike — 11 тестов в `BspReplacementSpikeTest` — выполнен и пройден до начала имплементации
custom processor'а. Все 8 acceptance criteria из плана зафиксированы.

| # | Acceptance | Факт SDK 1.61.0 |
|---|------------|------------------|
| 1 | Количество SpanExporter | Default OTel agent: один exporter (OTLP). Multi возможен через `otel.traces.exporter=a,b` — customizer вызывается N раз |
| 2 | Какой SpanProcessor приходит | OTLP exporter → `io.opentelemetry.sdk.trace.export.BatchSpanProcessor`; **logging exporter → `SimpleSpanProcessor`** (важно: instanceof guard ловит только batch-friendly случай) |
| 3 | instanceof BatchSpanProcessor | Надёжно разделяет случаи, когда замена имеет смысл (есть очередь + worker) |
| 4 | Worker leak | Стоковый BSP корректно закрывается через `processor.shutdown().join(timeout)` внутри customizer |
| 5 | Double-export | **Подтверждено**: возврат другого `SpanProcessor` из customizer полностью отбрасывает стоковый. Тест `replacingStockBspRoutesExportThroughReplacementOnly` — экспорт идёт только через replacement |
| 6 | Multi-exporter | Customizer вызывается по разу на каждый exporter; для multi → fallback UPSTREAM с WARN |
| 7 | Порядок вызова | **exporter customizer строго раньше** processor customizer (тест `exporterCustomizerCalledBeforeProcessorCustomizer`) |
| 8 | Env-var | `ConfigProperties` штатно нормализует только `OTEL_*`. Для `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` потребуется env-fallback в `addPropertiesSupplier`: `System.getenv("PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY")` → запись под именем `platform.tracing.queue.overflow-policy` |

**Вердикт spike: GO** — дизайн «capture exporter + conditional replace processor + explicit shutdown
стокового BSP» технически реализуем на pinned SDK 1.61.0.

## Контракт `PlatformDropOldestExportSpanProcessor`

### Очередь

- Bounded FIFO, хранит `SpanData` snapshots (не `ReadableSpan` ссылки — избегает lifecycle/visibility проблем)
- `maxQueueSize` из `otel.bsp.max.queue.size` (общий ключ с stock BSP)
- На `onEnd`: если `size >= maxQueueSize` → `poll()` oldest → `offer` new
- Non-blocking w.r.t. exporter (короткая ограниченная критическая секция для атомарного eviction
  допускается — на базе `ArrayDeque` + `ReentrantLock` или эквивалента)

### Worker

- Triggers flush:
  - размер batch достиг `maxExportBatchSize` → export немедленно
  - истёк `scheduleDelay` от последнего export → export текущего snapshot
  - `forceFlush` / `shutdown` → внеочередной export
- `exporter.export(batch)` возвращает `CompletableResultCode`; worker ограничивает ожидание
  через `join(exportTimeout)`. Превышение → инкремент `exportTimeouts`

### `forceFlush`

- Дренирует текущий snapshot в exporter
- Ожидает завершения in-flight export с `exportTimeout`
- Возвращает `ofFailure()` при timeout / export failure
- Не управляет вытеснением новых span'ов во время flush

### `shutdown`

- **Idempotent** (повторный вызов — no-op, возвращает закэшированный результат)
- Прекращает приём новых span'ов (`onEnd` → `droppedSpansAfterShutdown` counter)
- Дренирует очередь до пустоты или до `shutdownTimeout` (документированная shutdown policy)
- Терминирует worker thread (`join` с timeout, `interrupt` при превышении)
- Возвращает агрегированный `CompletableResultCode`

### Counters (обязательные)

| Имя | Тип | Семантика |
|-----|------|-----------|
| `droppedSpansOverflow` | `AtomicLong` | Span'ы, вытесненные при переполнении (production hot path) |
| `droppedSpansAfterShutdown` | `AtomicLong` | Span'ы, отброшенные после `shutdown()` (диагностика graceful окна) |
| `exportFailures` | `AtomicLong` | Неуспешные `exporter.export()` (любая причина) |
| `exportTimeouts` | `AtomicLong` | Подкатегория: превышение `exportTimeout` |
| `queueCapacity` | `int` getter | configured `maxQueueSize` (immutable) |
| `queueSize` | `int` getter | текущий размер очереди |

Геттеры экспонируются сразу; интеграция с Micrometer / actuator / JMX — follow-up. Без счётчиков
opt-in processor не наблюдаем в проде → блокер.

### Internal exception isolation

`PlatformDropOldestExportSpanProcessor` внутренне изолирует все исключения exporter и worker:

- Ни одно исключение из `onEnd` / export worker не покидает application-поток
- Обработка: `try/catch` → throttled WARN → counter

`SafeSpanProcessor` wrap не применяется (это enrichment-обёртка; export-side изоляция реализована внутри).

### Builder validation (production-safe: WARN + safe fallback, не fail-fast)

| Параметр | Правило | Fallback |
|----------|---------|----------|
| `maxQueueSize` | `> 0` | `DropOldestExportProcessorDefaults.defaultMaxQueueSize()` (internal `OtelSdkDefaults`) |
| `maxExportBatchSize` | `> 0` и `<= maxQueueSize` | `min(default, maxQueueSize)` |
| `scheduleDelay` | `> 0` | default |
| `exportTimeout` | `> 0` | default |

Fail-fast не выбран намеренно: agent extension не должен ронять JVM из-за конфиг-опечатки.

## Конфигурация

| Канал | Ключ | Допустимые | Default v1.x |
|-------|------|------------|--------------|
| Agent / extension | `platform.tracing.queue.overflow-policy` | `UPSTREAM`, `DROP_OLDEST` | `UPSTREAM` (= unset) |
| Env-var (fallback) | `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` | same | unset |
| Spring (desired UX) | `platform.tracing.queue.policy` | `DROP_OLDEST`, `DROP_NEWEST` (как в v0.1.0) | `DROP_OLDEST` (aspirational) |

**`DROP_NEWEST` на стороне extension НЕ вводится в v1.x.** Обоснование: пользователь, задавший
`PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=DROP_NEWEST`, ожидал бы гарантированную platform-policy.
Custom `DROP_NEWEST` processor не реализован → значение было бы alias на stock BSP, что
вводит в заблуждение. Spring enum `DROP_NEWEST` остаётся legacy/desired (UX), не привязан к agent.

**Naming convention:** префикс `OTEL_*` зарезервирован за стандартными свойствами OTel SDK;
платформенные property — собственный namespace `platform.tracing.*` / `PLATFORM_TRACING_*`.

**Расхождение enum'ов между Spring и Extension — сознательное:** Spring описывает desired policy
(UX), extension — runtime поведение (включая `UPSTREAM`). Согласование обеспечивает
`DropOldestAspirationDiagnostics` (см. ниже).

### Multi-exporter ограничение

Custom DROP_OLDEST opt-in **поддерживает только single effective exporter**. При multi-exporter:

- WARN при инициализации
- Fallback на UPSTREAM (без падения)
- Документировать: multi-exporter — не best-practice; для fan-out рекомендуется OTel Collector

## Diagnostics: aspiration warning (explicit-only)

`DropOldestAspirationDiagnostics` в `platform-tracing-spring-boot-autoconfigure`:

| Spring policy | Источник | Agent property | Сигнал |
|---------------|----------|---------------|--------|
| `DROP_OLDEST` | default (не задан явно) | unset | **no WARN**, актуатор-поле + опционально INFO раз |
| `DROP_OLDEST` | explicit override | unset / `UPSTREAM` | **WARN** (aspiration unmet) |
| `DROP_OLDEST` | любой | `DROP_OLDEST` | **no WARN** (выровнено) |
| `DROP_NEWEST` | любой | любой | вне scope |

Explicit detection — через `ConfigurableEnvironment.getPropertySources()`: property отсутствует
в пользовательских source'ах (только `defaultProperties` / Spring binding default) ⇒ default.

**Принципиально:** диагностика **не должна шуметь** на default-конфигурации (Spring
`policy=DROP_OLDEST` зафиксирован как default в `TracingProperties` v0.1.0). Иначе каждый сервис
получает WARN на старте → operational noise → диагностику отключают.

Флаги управления:

- `platform.tracing.diagnostics.drop-oldest-aspiration-warn` (default `true`)
- `platform.tracing.diagnostics.drop-oldest-aspiration-info` (default `true`)

## Тестовая стратегия

| Тест | Модуль | Назначение |
|------|--------|-----------|
| `BoundedDropOldestQueueTest` | extension | Строгий unit eviction: N+1 enqueue → size==N, oldest удалён |
| `DropOldestExportSpanProcessorOverflowPolicyTest` | extension | Integration: tail of sequence present, prefix absent, != stock drop-new snapshot |
| `DropOldestExportSpanProcessorLifecycleTest` | extension | forceFlush/shutdown idempotency, worker termination, counters |
| `DropOldestExportSpanProcessorTriggerTest` | extension | Batch/scheduleDelay flush, exportTimeout через CompletableResultCode.join |
| `DropOldestExportSpanProcessorBuilderValidationTest` | extension | Invalid config → WARN + safe fallback |
| `PlatformAutoConfigurationCustomizerExportProcessorTest` | extension | Default off / explicit on / multi-exporter fallback |
| `BatchSpanProcessorOverflowPolicyProbeTest` | core | **Сохраняется** — guardrail stock BSP |
| `DropOldestAspirationDiagnosticsTest` | spring-boot-autoconfigure | Explicit-only WARN matrix |
| `BspOverflowSafetyAgentSmokeTest` | e2e | Default v1.x = stock BSP (без изменений) |
| `BspDropOldestSafetyAgentSmokeTest` | e2e | **Обязательный gate.** Opt-in + unavailable OTLP → safety markers + INFO |
| `BspDropOldestNoDoubleExportTest` | extension/e2e | **Отдельный** от safety smoke — через in-process exporter stub либо customizer-level assertion |

## Предупреждения

### DROP_OLDEST ≠ error-priority

> **DROP_OLDEST does not mean error-preserving.** Under overload, an old error span may be evicted
> in favor of a newer successful span. Error retention remains a Collector tail-sampling / future
> priority-queue concern (backlog).

### Custom processor — это BSP-lite

`PlatformDropOldestExportSpanProcessor` берёт на себя ответственность BSP: bounded queue, worker,
batch export, timeouts, forceFlush, shutdown, failure isolation, counters. Это не «маленькая
обёртка» — поэтому полный набор lifecycle/trigger тестов обязателен.

### Pattern, not source copy

Worker / batch / timeout — переиспользование **паттерна** (идеи) из SDK BSP 1.61.0. Копирование
исходников запрещено guardrail'ом `OtelDirectIntegrationRules` («no local copies of OTel SDK
classes»). Контракт описывается собственными тестами.

## Rollback

Снятие env-var / sys-prop `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` восстанавливает default
(stock BSP). Никаких других изменений конфигурации не требуется.

## Out of scope (backlog)

- Default `DROP_OLDEST` в v1.x (рассмотреть в v2.x)
- Guaranteed custom `DROP_NEWEST` processor
- Multi-exporter support в DROP_OLDEST opt-in (рекомендация — Collector)
- Priority queue / error-first eviction
- `processor-mode: upstream|platform` split в Spring
- Enum rename `UPSTREAM`/`SDK_DEFAULT` в Spring (breaking)
- Замена `PlatformCompositeSpanProcessor`
- SDK-only prod без agent
- Полный R1 Spring→Agent bridge для queue-полей
- Micrometer/JMX интеграция counters (геттеры экспонируются; интеграция — follow-up)

## При upgrade SDK

Пересмотреть spike (`BspReplacementSpikeTest`):

- Изменился ли тип processor для OTLP exporter (всё ещё `BatchSpanProcessor`?)
- Изменился ли порядок вызова customizer'ов
- Появились ли новые методы `AutoConfigurationCustomizer`, требующие обработки

При расхождении — обновить ADR или создать новый.
