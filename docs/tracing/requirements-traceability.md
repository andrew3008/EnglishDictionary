# Requirements traceability: производительность и отказоустойчивость (Фаза 17)

> Назначение: «требование → evidence → gate». Каждое performance/resilience-требование из
> `Traces Requests.txt` обязано иметь измерение, тест или явную GAP-строку с ADR-обоснованием.
>
> Функциональная traceability (требование → реализация) — в [traceability.md](traceability.md).
> Определения SLA, статпротокол и инварианты P1–P5 — в
> [ADR-performance-model.md](../decisions/ADR-performance-model.md).
> Сценарии Mx/S1 — в [performance-test-matrix.md](performance-test-matrix.md).
> Статусы бюджетов — в [performance-budgets.yaml](performance-budgets.yaml).

## Матрица

| REQ ID | Требование (источник) | Evidence | Gate |
|--------|------------------------|----------|------|
| REQ-PERF-CPU-001 | «CPU overhead < 3%» | M5: медиана Δ process CPU per core vs M0, N=3, steady state ≥ 10 мин; невалидно при шуме M0/M0 > 1% | **Hard** |
| REQ-PERF-MEM-001 | «Memory overhead < 10%» | Δ RSS (primary) + Δ heap committed (secondary), M5 + soak M9 | **Hard** |
| REQ-EXPORT-ASYNC-001 | §3.1 «Методы экспорта возвращают управление немедленно» | `QueueOfferBenchmark` (PR-1) + JFR ThreadPark app-потоков в M6/M8a (PR-4) = 0 на exporter path | **Hard** (инвариант P1) |
| REQ-TIMEOUT-001 | §3 «таймаут ~100 мс на операции создания и экспорта» | Нормализовано в ADR-performance-model (Решение 2): hot-path — микробюджеты ns/µs из JMH; export — конфигурируемый timeout (отзывчивость закрыта async-экспортом) | Component warning (микробюджеты) |
| REQ-QUEUE-001 | §5 «фиксированная очередь … drop-oldest»; §6 «принудительный сброс при переполнении» | Queue saturation тест (обе ветки BSP/`PlatformDropOldestExportSpanProcessor`) + контрактные unit-тесты `PlatformDropOldestExportSpanProcessor*Test` (уже есть) | **Hard** (инвариант P2) |
| REQ-DEGRADED-001 | §2 «неблокирующее поведение, ошибки только логируются»; «автоматический fallback» | M6/M8a/M8b/M8c: p99 ≤ M0+50ms, без OOM, exit 0; `CollectorUnavailableResilienceTest` (уже есть, Фаза 16) | **Hard** (инварианты P1, P3) |
| REQ-BACKPRESSURE-001 | §3.4 «обработка сигналов 429/503» | M8b: RSS стабилен, drops по reason; политика «SDK без unbounded retries, durable retry — зона Collector» (ADR-safe-span-exporter-v1) | **Hard** |
| REQ-PRIORITY-001 | §3.5 «приоритизация errors > slow > success» | Retention: Collector tail_sampling (Фаза 16) — `CollectorPolicyContractTest`, `CollectorProductionPolicyE2ETest`. SDK-side priority eviction — **explicit backlog** (ADR-performance-model, Решение 4) | Retention: закрыто. Eviction: **GAP/backlog** (board) |
| REQ-SAMPLING-001 | «Header X-Trace-On → trace будет записан»; «% сэмплирования» | `CompositeSamplerBenchmark` (PR-1, ветки force-header/parent/ratio, @Threads 1/8) + macro header-сценарий в M5; функционально — e2e Фазы 16 (`force_header` 100% retention) | Component warning (перф) + функциональный e2e (уже зелёный) |
| REQ-DATA-001 | §2.1–2.3 SpanLimits (50 attrs / 1000 chars / 10 events); §2.4 masking | `SpanLimitsBenchmark` + `ScrubbingEngineBenchmark` (PR-1); функционально — `ScrubbingSpanProcessor` тесты + Collector redaction (Фаза 16) | Component warning (перф) + functional (уже зелёный) |
| REQ-WATCHDOG-001 | §2.5.1–2.5.2 span timeout 30s / trace timeout 60s | `SpanWatchdogProcessor` (функционально закрыто); вклад watchdog в overhead виден в M5 (входит в FULL) | Закрыто функционально; перф — в составе M5 |
| REQ-DYNCONF-001 | §4 «динамическое управление ratio на лету» | M10: p99/queue стабильны в окне reload (инвариант P5); `SamplerStateHolder` atomic publish (Фаза 14) | **Hard** (M10) |
| REQ-STARTUP-001 | implicit (k8s rollout/readiness) | S1: premain time + time-to-first-200 vs без агента | Evidence (бюджет — после первого замера) |
| REQ-KAFKA-001 / REQ-DB-001 | Kafka / Postgres tracing (раздел поддерживаемых технологий) | M13 / M12 evidence-прогоны | Evidence |

## GAP-перечень

| GAP | Описание | Решение |
|-----|----------|---------|
| GAP-PRIORITY-EVICTION | SDK-side приоритетное вытеснение из export-очереди не реализовано (drop-oldest вытесняет без учёта приоритета) | Board/backlog-решение; Фаза 17 собирает evidence по `QUEUE_OVERFLOW`-потерям (ADR-performance-model, Решение 4) |
| GAP-EXPORT-TIMEOUT-100MS | Буквальный export-timeout 100 мс не применяется (default 5000 ms) | Осознанная нормализация требования (ADR-performance-model, Решение 2); требование отзывчивости закрыто async-экспортом |

## Правила сопровождения

- Новое требование в `Traces Requests.txt` → новая строка REQ-* с evidence или GAP.
- Бюджет каждой Hard-строки существует в `performance-budgets.yaml` и валидируется
  `PerformanceBudgetsContractTest`.
- Изменение статуса GAP → обновление этой матрицы + ADR.
