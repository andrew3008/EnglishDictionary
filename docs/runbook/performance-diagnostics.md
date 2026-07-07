# Runbook: диагностика производительности трассировки (Фаза 17, PR-5)

> Формат — decision tree: каждый узел = наблюдаемый симптом → проверка (метрика/JMX) →
> следующий шаг или вердикт. JMX-атрибуты — `PlatformTracingControlMBean`
> (`space.br1440.platform.tracing:type=Control,name=PlatformTracingControl`);
> в Prometheus те же значения доступны через polling-MeterBinder Spring-стартера
> (см. [actuator-tracing-diagnostics.md](actuator-tracing-diagnostics.md)).
> Таксономия потерь — [dropped-span-reasons.md](../tracing/dropped-span-reasons.md).

## Дерево 1. «CPU сервиса вырос после включения трассировки»

```text
CPU выше ожидаемого
├─ 1. Δ vs M0-baseline > 3%? (сравнить с perf-results последнего sign-off)
│   ├─ нет → в пределах бюджета; см. budget m5-cpu (performance-budgets.yaml). СТОП.
│   └─ да ↓
├─ 2. Sampling ratio фактический? getSamplingRatio()
│   ├─ ratio = 1.0 → диагностический worst-case M5w, не штатный режим.
│   │   Действие: вернуть штатный ratio (setSamplingRatio / updateSamplingPolicy).
│   └─ ratio штатный ↓
├─ 3. Объём span'ов аномален? getSamplerDecisionCounts() (RECORD-доля),
│   getActiveSpanCount() — span explosion от noisy instrumentation (M11)?
│   ├─ да → найти источник (JDBC/Kafka инструментации, вложенные фасадные span'ы);
│   │   точечно droppedRoutes / route-ratios (updateSamplingPolicy).
│   └─ нет ↓
├─ 4. Scrubbing-вклад? Доля длинных строковых атрибутов высокая?
│   getScrubbingMetrics() (actions растут быстро) + профиль flamegraph:
│   value-based правила (jwt/email/ip) на длинных значениях — ×65 стоимость
│   (ADR-scrubbing-cost). Действие: проверить SpanLimits maxAttributeValueLength,
│   рассмотреть performance-профиль правил (board).
└─ 5. Иначе → снять async-profiler/JFR на reference-стенде (run-perf-scenario -Jfr),
    сравнить с flamegraph последнего sign-off; новая статья расхода = регрессия
    → jmhCompareBaseline по компонентам (sampler/scrubbing/queue offer).
```

## Дерево 2. «RSS растёт / подозрение на утечку»

```text
RSS растёт со временем
├─ 1. Heap или native? (heap committed vs VmRSS: gc.log + rss-cpu-samples.csv)
│   ├─ heap стабилен, RSS растёт → metaspace/code cache/нативные буферы агента;
│   │   снять NMT (-XX:NativeMemoryTracking=summary) на стенде. Не платформенная
│   │   очередь span'ов (она bounded: getExportQueueCapacity()).
│   └─ heap растёт ↓
├─ 2. Очередь экспорта? getExportQueueSize() ≈ capacity постоянно?
│   ├─ да → Collector деградирован (Дерево 3); очередь bounded — рост heap НЕ от неё.
│   └─ нет ↓
├─ 3. Незакрытые span'ы? getActiveSpanCount()/getActiveTraceCount() монотонно растут →
│   утечка SpanScope в коде приложения (try-with-resources отсутствует);
│   watchdog принудительно закроет (getForcedSpanCloses()), но генерация
│   мусора останется. Найти владельца span'ов в JFR OldObjectSample.
└─ 4. Иначе → M9 soak 60 мин на стенде (run-perf-scenario -Scenario m9 -SteadyMin 60 -Jfr):
    линейная регрессия slope > 1 MB/min = подтверждённая утечка; JFR OldObjectSample
    атрибутирует владельца. Кандидаты Фазы 17: ThreadLocal-кэши, MDC-зеркала.
```

## Дерево 3. «Collector недоступен / деградирован»

```text
Экспорт деградирован (рост exportFailures/exportTimeouts)
├─ 1. Приложение страдает? p99 запросов вырос > +50ms vs норма?
│   ├─ да → НАРУШЕНИЕ инварианта P1 (app-поток не должен ждать экспорт) — критический
│   │   дефект платформы, эскалация с JFR ThreadPark evidence.
│   └─ нет (ожидаемое поведение M6/M8) ↓
├─ 2. Категория деградации?
│   ├─ connection refused/DNS → M6: Collector down. Потери = EXPORT_FAILURE.
│   ├─ таймауты → M8a: slow Collector. getExportTimeoutsTotal() растёт.
│   ├─ 429/503 → M8b: backpressure. SDK не ретраит unbounded (зона durable retry —
│   │   Collector, ADR-safe-span-exporter-v1); потери учтены, RSS стабилен.
│   └─ чередование → M8c: flaky. Проверить отсутствие осцилляций RSS.
├─ 3. Оперативные действия (в порядке возрастания жёсткости):
│   a. снизить ratio:        setSamplingRatio(0.01)
│   b. выключить экспорт:    setExportEnabled(false)  → потери = EXPORT_GATE_DISABLED
│   c. kill-switch сэмплера: setSamplerEnabled(false) → потери = SAMPLED_OUT
│   (propagation НЕ трогать: setPropagationEnabled влияет на сквозную корреляцию).
└─ 4. После восстановления: вернуть конфигурацию, проверить getConfigAuditTrail()
    и сброс роста getExportDroppedOverflowTotal().
```

## Дерево 4. «Трейс не найден в Jaeger»

```text
Трейс отсутствует
├─ 1. SAMPLED_OUT? ratio/drop-paths; для гарантии — повторить с X-Trace-On: on
│   (force-record, 100% retention через Collector-политику force_header).
├─ 2. EXPORT_GATE_DISABLED? isExportEnabled() + getConfigAuditTrail().
├─ 3. QUEUE_OVERFLOW? getExportDroppedOverflowTotal() растёт → перегрузка (Дерево 3).
├─ 4. EXPORT_FAILURE/TIMEOUT? getExportFailuresTotal()/getExportTimeoutsTotal().
└─ 5. COLLECTOR_TAIL_DROP? обычные success-трейсы дропаются tail-sampling'ом по
    политике (приоритет errors/slow); это норма retention-модели Фазы 16.
    Проверка политик: CollectorPolicyContractTest / collector-pipeline-production.md.
```

## Пороговые значения

Все численные пороги (3% CPU, 10% RSS, +50ms p99, 1 MB/min slope) — версионируемые
данные [performance-budgets.yaml](../tracing/performance-budgets.yaml); runbook
не дублирует числа, чтобы исключить дрейф документов.
