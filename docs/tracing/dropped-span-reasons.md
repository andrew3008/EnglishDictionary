# DroppedSpanReason: таксономия точек потери span'ов (Фаза 17, PR-5)

> Принцип: таксономия — это **mapping поверх существующих счётчиков**, а не новые точки
> учёта. Каждая категория ссылается на реально существующую метрику/JMX-атрибут
> (`PlatformTracingControlMBean`, `OBJECT_NAME =
> space.br1440.platform.tracing:type=Control,name=PlatformTracingControl`).
> Категории без реальной точки потери в коде в таксономию НЕ входят.

## Категории

| Reason | Точка потери | Существующий счётчик / источник | Намеренная? |
|--------|--------------|--------------------------------|-------------|
| `SAMPLED_OUT` | Head-sampling: `CompositeSampler` вернул DROP (ratio / drop-paths / disabled) | `getSamplerDecisionCounts()` — ключи `DROP:<reason>`; Prometheus через polling-MeterBinder стартера | **Да** (политика) |
| `QUEUE_OVERFLOW` | Переполнение export-очереди: eviction oldest (`PlatformDropOldestExportSpanProcessor`) | `getExportDroppedOverflowTotal()` | Нет (потеря под нагрузкой) |
| `QUEUE_OVERFLOW_UPSTREAM` | Переполнение очереди stock BSP (политика `UPSTREAM`, drop-new) | Внутренний счётчик BSP (`processedSpans{dropped=true}`, SDK self-metrics); платформенного счётчика нет — это документированное ограничение ветки UPSTREAM | Нет |
| `EXPORT_FAILURE` | Экспорт не удался (исключение / failure-код OTLP-клиента) | `getExportFailuresTotal()`; `getSafeExporterMetrics()` ключи `export_batch_failures`, `transport_dropped_spans` | Нет (деградация) |
| `EXPORT_TIMEOUT` | Превышен `exportTimeout` батча | `getExportTimeoutsTotal()` (подкатегория failures) | Нет (деградация) |
| `EXPORT_GATE_DISABLED` | Kill-switch экспорта (`setExportEnabled(false)`): span'ы создаются, но отбрасываются на экспорте | `getSafeExporterMetrics()` ключ `suppressed_spans_export_disabled`; audit-trail `getConfigAuditTrail()` | **Да** (оперативное решение) |
| `SHUTDOWN` | Span поступил после `shutdown()` export-процессора | `getExportDroppedAfterShutdownTotal()` | Да (жизненный цикл) |
| `COLLECTOR_TAIL_DROP` | Tail-sampling политика Collector'а не выбрала трейс | Метрики Collector'а (`otelcol_processor_tail_sampling_*`); зона SRE, вне JVM | **Да** (политика) |

## Что потерей span'а НЕ является

| Событие | Почему не в таксономии | Счётчик |
|---------|------------------------|---------|
| Усечение атрибутов/событий по SpanLimits | Span доезжает усечённым | `getDroppedAttributesTotal()` / `Events` / `Links` |
| Scrubbing DROP атрибута | Теряется атрибут, не span | `getScrubbingMetrics()` ключ `drop` |
| Forced close watchdog'ом | Span экспортируется (с пометкой) | `getForcedSpanCloses()` / `getForcedTraceCloses()` |
| Ошибка делегата pipeline | Композит изолирует сбой, span продолжает путь | `getProcessorErrorsTotal()` / `ByName` |

## Порядок проверки при инциденте «трейс не найден»

```text
1. SAMPLED_OUT?         getSamplerDecisionCounts(), ratio, X-Trace-On
2. EXPORT_GATE_DISABLED? isExportEnabled(), getConfigAuditTrail()
3. QUEUE_OVERFLOW?      getExportDroppedOverflowTotal() растёт?
4. EXPORT_FAILURE/TIMEOUT? getExportFailuresTotal()/getExportTimeoutsTotal(), доступность Collector
5. COLLECTOR_TAIL_DROP? метрики tail_sampling, политика приоритетов (errors/slow → 100%)
```

Полный диагностический decision tree — [performance-diagnostics.md](../runbook/performance-diagnostics.md).

## Связь с перф-моделью

- Сценарии M6/M8a–M8c/queue-saturation (PR-4) обязаны показывать рост СООТВЕТСТВУЮЩЕЙ
  категории (например, queue-saturation → `QUEUE_OVERFLOW`), иначе учёт потерь неполон.
- Evidence по `QUEUE_OVERFLOW`-потерям — вход board-решения о SDK-side priority eviction
  (GAP-PRIORITY-EVICTION, ADR-performance-model Решение 4).
