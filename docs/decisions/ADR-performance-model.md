# ADR: Performance Assurance Model (Фаза 17)

| Поле | Значение |
|------|----------|
| Статус | **Accepted** |
| Дата | 2026-06-10 |
| Источник требований | `Platform_Traces_Archive\Traces Requests.txt` (единственный авторитетный документ) |
| Связанные документы | [performance-test-matrix.md](../tracing/performance-test-matrix.md), [requirements-traceability.md](../tracing/requirements-traceability.md), [performance-budgets.yaml](../tracing/performance-budgets.yaml), [h1-composite-pipeline-jmh-baseline.md](../tracing/h1-composite-pipeline-jmh-baseline.md) |
| Связанные ADR | [ADR-drop-oldest-export-processor-v1.md](ADR-drop-oldest-export-processor-v1.md), [ADR-bsp-overflow-policy-finding.md](ADR-bsp-overflow-policy-finding.md), [ADR-safe-span-exporter-v1.md](ADR-safe-span-exporter-v1.md), [ADR-collector-boundary.md](ADR-collector-boundary.md) |

## Контекст

Требования (`Traces Requests.txt`) задают не «tracing starter», а **runtime-компонент с SLO на
собственное потребление ресурсов**: `CPU overhead < 3%`, `Memory overhead < 10%`, неблокирующее
поведение, асинхронный экспорт, таймаут операций ~100 мс, bounded queue + drop-oldest,
приоритизация errors > slow > success, masking чувствительных данных, динамическая конфигурация.

До Фазы 17 эти требования имели статус «Out of Java scope — проверяет SRE» (см.
`docs/tracing/traceability.md` §2). Фаза 17 переводит их в **доказуемую модель**:
требования → бюджеты → измерения → evidence-артефакты → release gates → runbook → waiver-процесс.

Принцип OpenTelemetry, на котором построена модель: клиентская библиотека **не должна блокировать
приложение** и **не должна потреблять unbounded memory**; при конфликте между сохранением всей
телеметрии и защитой приложения допустимо контролируемое dropping-поведение с метрикой.

## Решение 1. Точные определения SLA

Без точного числителя/знаменателя «CPU < 3%» неверифицируем. Фиксируем:

### CPU overhead

```text
CPU overhead = Δ(process CPU time user+sys, normalized per core) vs M0
Условия: фиксированный arrival rate (open model), steady state >= 10 мин,
         warmup >= 2 мин, медиана N=3 прогонов.
Источник: cgroup cpuacct (Linux-контейнер SUT), /proc/<pid>/stat.
```

### Memory overhead

```text
Memory overhead = Δ RSS (primary) + Δ heap committed (secondary) на steady state vs M0.
RSS: /proc/<pid>/status VmRSS — включает heap, metaspace, code cache,
     нативные буферы агента, thread stacks.
Heap-only метрика НЕ принимается как доказательство Mem < 10%.
```

### Протокол сравнения

- Все сравнения — **same host, same run session**;
- порядок конфигов — **interleaved** (M0 → M5 → M0 → M5 …) для контроля теплового/фонового дрейфа;
- каждый результат несёт метаданные: `gitSha`, `JDK`, `hardwareProfileId`, `benchmarkConfigId`.

### Режимы SLA

| Режим | Конфигурация | Статус гейта |
|-------|--------------|--------------|
| **Normal** = M5 | FULL extension, sampling ratio 0.1, Collector healthy | **Hard gate**: CPU Δ < 3%, RSS Δ < 10% |
| **Worst case** = M5w | FULL, ratio 1.0 | Evidence-only; отдельный бюджет — решение board |
| **Degraded** = M6/M8x | Collector down / slow / 429-503 / flaky | **Hard gate**: p99 ≤ M0 p99 + 50 мс, без OOM, exit 0 |

## Решение 2. Нормализация требования «таймаут 100 мс»

Требование §3 («На все операции создания и экспорта span'а устанавливается ограничение по времени
(ориентировочно 100 мс)») **нельзя применять одинаково** к hot-path и экспорту:

| Операция | Бюджет | Обоснование |
|----------|--------|-------------|
| `span.start` / `span.end` / sampler / queue offer | **микробюджеты в ns/µs** (фиксируются по JMH PR-1) | hot-path не должен содержать blocking I/O вообще; 100 мс как бюджет здесь бессмыслен (3+ порядка запаса) и маскировал бы деградацию |
| export / flush / degraded operations | **100 мс** = upper bound (`platform.tracing.queue.export-timeout`, default уже 100 ms → 5000 ms выровнен Wave R1+, см. ниже) | единственный слой, где время операции зависит от сети/Collector |

**Платформенное ужесточение vs OTel default:** стандартный BSP имеет `OTEL_BSP_EXPORT_TIMEOUT=30000 ms`.
Текущий платформенный default — 5000 ms (agent BSP export timeout via `PlatformTracingDefaultsProvider` / internal `OtelSdkDefaults`,
dual-channel alignment Wave R1+). Сжатие до 100 мс — **осознанное ужесточение** с известным
риском: при медленном Collector растут `exportTimeouts` и drops. Решение: 100 мс трактуем как
требование к **отзывчивости приложения** (закрыто асинхронным экспортом и быстрым возвратом
управления, §3.1), а не как обязательный export-timeout; export-timeout остаётся
конфигурируемым (5000 ms default). Это расхождение зафиксировано здесь и в traceability.

## Решение 3. Performance Safety Invariants (P1–P5)

Инварианты — проверяемые свойства, а не декларации. Каждый привязан к тесту/бенчу:

| # | Инвариант | Проверка |
|---|-----------|----------|
| P1 | Application-поток никогда не ждёт подтверждения Collector'а (никаких remote-вызовов на hot path sampler/processor) | `QueueOfferBenchmark` (PR-1); JFR ThreadPark на app-потоках в M6/M8 (PR-4) |
| P2 | Queue-offer путь bounded по времени и памяти | `QueueOfferBenchmark` p99 (SampleTime); saturation-тест PR-4: queue size ≤ capacity |
| P3 | Collector-down не вызывает unbounded memory growth | M6 под sustained-нагрузкой: RSS стабилен, без OOM (PR-4) |
| P4 | Плохое scrubbing-правило не вызывает catastrophic backtracking без отсечения (валидация на config-load; паттерн SonarSource RSPEC-5852) | `ScrubbingEngineBenchmark` long-value сценарий (PR-1); `RuleCircuitBreaker` (уже в проде) |
| P5 | Dynamic config reload не блокирует span start/end (immutable snapshots + atomic publish, Фаза 14) | M10: p99 и queue стабильны во время update (PR-3/PR-4); `SamplerStateHolder.tryUpdate` lock-free контракт |

## Решение 4. Приоритизация errors > slow > success (§3.5)

Разделяем два разных механизма, которые требование смешивает:

1. **Priority retention** (какие трейсы доезжают до хранилища) — **реализовано, Фаза 16**:
   `ClassificationSpanProcessor` проставляет `platform.trace.priority=high` для error/slow
   span'ов; Collector `tail_sampling` политики `errors-always-sample`, `slow-traces`,
   `platform-high-priority` гарантируют 100% retention. Evidence: `CollectorPolicyContractTest`,
   `CollectorProductionPolicyE2ETest`.
2. **SDK-side priority eviction** (кого вытеснять из локальной export-очереди при переполнении) —
   **explicit backlog**. `PlatformDropOldestExportSpanProcessor` вытесняет строго oldest без
   учёта приоритета (см. предупреждение «DROP_OLDEST ≠ error-priority» в
   ADR-drop-oldest-export-processor-v1). Фаза 17 только фиксирует feasibility: приоритетная
   очередь = O(log n) offer или двухуровневая очередь; усложнение hot-path конфликтует
   с микробюджетами P2. Реализация — отдельное board-решение после prod-метрик
   `QUEUE_OVERFLOW`-потерь.

## Решение 5. Статистический протокол

```text
1. N = 3 прогона на конфигурацию, агрегат — медиана.
2. Шум лаборатории калибруется парой M0/M0 (два независимых прогона M0):
   если Δ(M0, M0) CPU > 1% — хост непригоден для подтверждения 3%-SLA.
3. Если CV (coefficient of variation) по 3 прогонам M0 > 5% — N увеличивается до 5.
4. Перцентили латентности — только из HDR histogram артефактов (защита от
   coordinated omission), не из усреднённых агрегатов генератора.
5. JMH-микробенчи: только gc.alloc.rate.norm как alloc-метрика (метрика без .norm
   имеет известное искажение GC-профайлера JMH); forks >= 2; SampleTime для
   latency-чувствительных бенчей.
```

**Граница ответственности JMH:**

```text
JMH детектирует локальные регрессии и компонентную alloc-стоимость.
JMH сам по себе НЕ одобряет релиз по производительности.
Release-approval требует macro open-model прогонов M0/M5/M6/M8/M9/S1.
```

**`jmhCompareBaseline` gate policy (PR-6C3):**

```text
Hard gate (blocks -PjmhFailOnRegression):
  - avgt latency: WARN +10%, FAIL +25%
  - gc.alloc.rate.norm (all modes): WARN +5%, FAIL +15%

Diagnostic (reported, not blocking by default):
  - sample-mode latency (SampleTime tail): same thresholds, logged as DIAG WARN/FAIL

Optional strict sample gate: -PjmhFailOnSampleRegression=true (with -PjmhFailOnRegression).
Tail latency release confidence — macro HDR/JFR evidence, not JMH sample mode alone.
```

## Решение 6. Бюджеты как версионируемые данные

`docs/tracing/performance-budgets.yaml` — policy-файл (не Java-код): `referenceLab`
(hostId, шумовой порог, steady-state минимум, hardware fingerprint), `budgets[]`
(id, requirement-ссылка, scenario, metric, budget, owner, status `PASS|WAIVER|PENDING`,
evidence-ссылка), `waivers[]` (budgetId, reason, **expires**, approvedBy, evidence).

Контракт валидируется JVM-тестом `PerformanceBudgetsContractTest`
(`platform-tracing-bench/src/test`, паттерн `CollectorPolicyContractTest`):
схема полей, отсутствие просроченных waivers, каждый hard-gate бюджет ссылается
на существующее требование из traceability.

Governance: изменение бюджета = PR с ревью владельца компонента; waiver обязан иметь
expiry-дату и ссылку на решение board; release-гейт фейлится при `PENDING`-строках
hard-гейтов и просроченных waivers.

## Решение 7. Трёхуровневая модель гейтов

| Уровень | Состав | Поведение |
|---------|--------|-----------|
| **Release hard gates** | M5 CPU Δ < 3%; M5 RSS Δ < 10%; M6/M8 без OOM, exit 0, p99 ≤ M0+50ms; queue ≤ capacity; app-поток не ждёт exporter I/O; M9 soak без роста (slope ≤ ~1 MB/min) | блокируют релиз |
| **Component warning gates** | sampler p99 стабилен под `@Threads(8)`; queue offer p99 < микробюджет; clean-key scrubbing ≈ allocation-free относительно hit-path; MDC/Context cost задокументирован | WARN в `jmhCompareBaseline`, эскалация при повторении |
| **Mandatory evidence (без гейта)** | M5w (ratio 1.0); S1 startup; M11 noisy instrumentation; M12 Postgres; M13 Kafka; flamegraphs near-budget прогонов; JFR M5/M6/M9 | обязательные артефакты sign-off |

## Risk register

| # | Риск | Митигация |
|---|------|-----------|
| R1 | Single-host лаборатория (Gentoo `192.168.100.70`) — SPOF и единственная точка шумовой калибровки | M0/M0-протокол; открытый вопрос board о втором хосте |
| R2 | Windows-хост непригоден для официальных цифр (нет cgroup, нет async-profiler; RSS только через JVM API) | Windows = smoke-tier; официальные прогоны — только Linux |
| R3 | Контейнерная co-location k6 + SUT + Collector искажает Δ CPU | cpuset-квоты на контейнеры, validity gate на throttling |
| R4 | JMH-микробенчи создают «ложную точность» (JIT-профиль изолирован от реального workload) | JMH = regression detection only; release — macro-прогоны |
| R5 | Committed baseline JSON ломается при смене хоста | baselines per `hardwareProfileId` (`cpuModel`, `cpuCores`, `ramGB`, `osKernel`) |

## Открытые вопросы для board

1. Gentoo `192.168.100.70` — официальная reference-лаборатория? Нужен ли второй хост?
2. Target arrival rate для SLA (req/s) — production-прогноз или дефолт 500 req/s?
3. Какая CI-система планируется (определяет форму активации Gradle-гейтов)?
4. M5w (ratio 1.0): отдельный бюджет или постоянный waiver?
5. Drop-oldest: остаётся текущая семантика (`PlatformTracingDefaultsProvider` →
   `DROP_OLDEST`, откат через `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`) или
   фиксируем как неизменяемый compliance-target? Matrix измеряет обе ветки равноправно.
6. Scrubbing-профиль по умолчанию: strict-default или performance-default
   (по данным [ADR-scrubbing-cost.md](ADR-scrubbing-cost.md))?

## Последствия

- Появляются модуль-инструменты: расширенный `platform-tracing-bench` (PR-1) и gated
  `platform-tracing-perf-tests` (PR-3/PR-4); ни один не публикуется.
- Строка traceability «CPU < 3%, Memory < 10% — Out of Java scope» заменяется ссылкой
  на performance-модель: платформа предоставляет evidence, SRE — окружение.
- Релизный чек-лист `SUPPORTED.md` дополняется performance-гейтами (PR-6).
