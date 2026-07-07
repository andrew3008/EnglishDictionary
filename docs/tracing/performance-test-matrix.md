# Performance test matrix (Фаза 17, v3)

> Источник определений SLA и статпротокола: [ADR-performance-model.md](../decisions/ADR-performance-model.md).
> Все macro-конфигурации гоняются open-model генератором (k6 `constant-arrival-rate`),
> steady state ≥ 10 мин, warmup ≥ 2 мин, N=3, медиана. Прогон без выполненных
> validity gates (см. ниже) не идёт в sign-off.

## Sign-off tier (обязательные для релиза)

| ID | Конфигурация | Что доказывает | Гейт |
|----|--------------|----------------|------|
| M0 | Без агента и extension | Baseline; пара M0/M0 калибрует шум хоста | Δ(M0,M0) CPU ≤ 1%, иначе лаборатория непригодна |
| M4 | Agent + extension MINIMAL (sampler + базовые процессоры, без scrubbing) | Стоимость каркаса | evidence |
| M5 | Agent + extension FULL, ratio 0.1, Collector healthy | **Normal mode SLA** | **Hard: CPU Δ < 3%, RSS Δ < 10% vs M0** |
| M5w | FULL, ratio 1.0 | Worst case (диагностический режим) | evidence; бюджет — решение board |
| M6 | Collector down (unreachable endpoint) | Деградация без вреда приложению (P1, P3) | **Hard: p99 ≤ M0+50ms, без OOM, exit 0** |
| M8a | Slow Collector (delayed 200/OK) | Queue растёт, app-потоки не блокируются | **Hard: нет ThreadPark app-потоков на exporter path** |
| M8b | Overloaded Collector (HTTP 429/503) | Backpressure без unbounded retry | **Hard: RSS стабилен, drops учитываются по reason** |
| M8c | Flaky Collector (чередование success/timeout) | Отсутствие осцилляций/утечек на ретраях OTLP-клиента | **Hard: без OOM, exit 0** |
| M9 | Soak 60 мин (FULL, ratio 0.1) | Отсутствие медленных утечек | **Hard: линейная регрессия RSS slope ≤ ~1 MB/min** |
| M10 | Runtime config reload под нагрузкой (ratio change, scrubbing toggle, export gate) | P5: reload не блокирует hot-path | **Hard: p99 и queue стабильны в окне reload** |
| M10c | Config storm: валидные ratio-апдейты каждые ~2с всю steady-фазу (`PERF_RELOAD_STORM=valid`) | ADR-runtime-sampling-policy C-2/C-4/C-7: шторм апдейтов не стопорит hot-path | evidence (non-gating): p99/queue стабильны; `configVersion` монотонно растёт (reload-storm.csv) |
| M10d | Invalid config storm: ratio=5.0 каждые ~2с (`PERF_RELOAD_STORM=invalid`) | ADR-runtime-sampling-policy C-5: last-known-good под потоком невалидных конфигураций | evidence (non-gating): ratio/`configVersion` неизменны, `invalidConfigCount` растёт, p99 стабилен |
| S1 | Startup: premain time + time-to-first-200 (vs без агента) | Стоимость старта для k8s readiness | evidence; бюджет фиксируется после первого замера |
| Queue saturation | `OTEL_BSP_MAX_QUEUE_SIZE=32`, обе ветки: stock BSP (drop-new) и `PlatformDropOldestExportSpanProcessor` (drop-oldest) | P2: bounded queue; сравнение overflow-политик | **Hard: queue ≤ capacity, heap стабилен; рост `droppedOverflow`** |

## Evidence tier (обязательные артефакты, без hard-гейта)

| ID | Конфигурация | Зачем |
|----|--------------|-------|
| M11 | Noisy instrumentation: nested facade spans, высокий span volume | Доказать, что typed API не провоцирует span explosion |
| M12 | Postgres/JDBC tracing (agent instrumentation) | JDBC резко увеличивает span volume; overhead отличен от HTTP |
| M13 | Kafka producer/consumer/batch listener | Batch может создавать много spans/events на одну бизнес-операцию |

## Diagnostic tier (по запросу, НЕ для sign-off)

| ID | Конфигурация | Когда запускать |
|----|--------------|------------------|
| M1 | OTel API only, без export | Декомпозиция overhead при расследованиях |
| M2 | OTel SDK, без export | — " — |
| M3 | OTel SDK + stock BSP + OTLP (без платформенного extension) | Изоляция стоимости extension vs vanilla OTel |

## Validity gates прогона

Прогон признаётся **невалидным** (не идёт в sign-off, перезапускается), если:

```text
- фактический arrival rate отклоняется > 1% от target;
- k6 dropped_iterations > 0;
- CPU-throttling SUT-контейнера (cgroup cpu.stat throttled_usec) выше порога;
- Collector делит cpuset с SUT;
- отсутствуют GC-логи / JFR recording / RSS-сэмплы;
- warmup < 2 мин или steady state < 10 мин.
```

## Метрики per run (обязательный состав)

```text
process.cpu user/sys seconds            cgroup cpu.stat throttled_usec
RSS (/proc/<pid>/status VmRSS)          heap committed/used
metaspace committed/used                code cache used
thread count                            GC pause p95/p99
allocation rate MB/s                    queue utilization p50/p95/max
oldest span age                         dropped spans by reason
export duration p95/p99                 export timeout/failure counters
HTTP p50/p95/p99 + raw HDR histogram artifact (per run)
```

## Топология стенда (Gentoo `192.168.100.70`)

```text
SUT-контейнер:        выделенные ядра (cpuset), фиксированные -Xmx/-Xms
k6-контейнер:         отдельные ядра
Collector-контейнер:  отдельные ядра (или отдельный хост)
Хостовая ОС:          оставшиеся ядра
```

Конкретная cpuset-схема фиксируется в `docker-compose.perf.yml` (PR-3) и в метаданных прогона.

## Артефакты прогона

Каждый прогон сохраняет в `docs/tracing/perf-results/<date>/<scenario>/`:
`summary.json` (метрики + метаданные: gitSha, JDK, hardwareProfileId, configId),
`*.hdr` (raw histogram), `*.jfr` (recording), `gc.log`, `rss-samples.csv`,
k6 summary. Flamegraphs (async-profiler) — обязательны для прогонов с Δ CPU > 2%
или alloc выше WARN-порога.
