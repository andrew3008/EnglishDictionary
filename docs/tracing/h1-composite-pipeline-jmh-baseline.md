# H1 Baseline: CompositePipelineBenchmark (Wave B)

| Поле | Значение |
|------|----------|
| Дата | 2026-06-10 |
| Риск | **H1** — overhead `ReadWriteSpan.toSpanData()` на hot-path `onEnding` |
| Артефакт | `platform-tracing-bench/CompositePipelineBenchmark` |
| Результаты (raw) | `platform-tracing-bench/build/results/jmh/results.txt` |
| Решение | **ProcessorContext refactor НЕ делаем** (anti-over-engineering); пересмотр — только при prod-боли |

## Контекст

Architecture review (H1) зафиксировал, что четыре реальных процессора композита независимо
вызывают `span.toSpanData()` в `onEnding`, материализуя полный immutable-снимок span'а:

| Процессор | Вызов `toSpanData()` | Зачем |
|-----------|---------------------|-------|
| `EnrichingSpanProcessor` | `onEnding` (status; remote-service на CLIENT+ERROR) | `getStatus()`, `getTraceId()` |
| `ScrubbingSpanProcessor` | `onEnding` | `getAttributes()` — обход всех атрибутов |
| `ValidatingSpanProcessor` | `onEnding` → `isPresent()` | fallback `getResource().getAttribute()` |
| `ClassificationSpanProcessor` | `onEnding` | `getStatus()` |

До Wave B бенчмарк использовал 4 **noop**-делегата и не измерял реальную работу процессоров
и аллокации `toSpanData()`.

## Методология

**Запуск:**

```bash
./gradlew :platform-tracing-bench:jmh -PjmhInclude=CompositePipelineBenchmark
```

**Параметры JMH:** 2 forks × 5 warmup × 5 measurement, `avgt` (ns/op), GC-профайлер
(`gc.alloc.rate.norm`, байт/op).

**Окружение:** JDK 21, Windows 10, in-JVM (без Docker, без экспортёра, без Agent).
Docker **не требуется** для JMH.

**Четыре замера (декомпозиция):**

| # | Метод | Что измеряет |
|---|-------|--------------|
| 1 | `compositeObvyazka` | 4 noop-делегата, пустой span — чистая обвязка композита |
| 2 | `prodLikeSpanNoopPipeline` | 4 noop + ~9 prod-like атрибутов (HTTP-server + 2 sensitive) |
| 3 | `prodLikeSpanRealPipelineNoScrubbing` | Enriching + Validating + Classification (все вызывают `toSpanData()`, минимум собственной работы) — **изоляция H1-поверхности** |
| 4 | `prodLikeSpanRealPipeline` | Полный production-композит (+ Scrubbing, 12 built-in rules) |

## Результаты (2026-06-10)

| Benchmark | Score (ns/op) | Error | gc.alloc.rate.norm (B/op) |
|-----------|--------------:|------:|--------------------------:|
| `compositeObvyazka` | 216 | ±111 | 720 |
| `prodLikeSpanNoopPipeline` | 340 | ±70 | 1 320 |
| `prodLikeSpanRealPipelineNoScrubbing` | **2 365** | ±265 | **2 896** |
| `prodLikeSpanRealPipeline` | **14 023** | ±304 | **18 576** |

### Дельты (prod-like span)

| Сравнение | Δ ns/op | Δ B/op | Интерпретация |
|-----------|--------:|-------:|---------------|
| noop → prod-like attrs (noop pipeline) | +124 | +600 | Стоимость установки ~9 атрибутов через фасад |
| noop prod-like → real **без scrubbing** | **+2 025** | **+1 576** | **H1-поверхность:** 3× `toSpanData()` + обогащение/валидация/классификация |
| real no-scrubbing → **full real** | **+11 658** | **+15 680** | **Scrubbing:** regex-движок 12 rules × обход атрибутов + 4-й `toSpanData()` |
| noop prod-like → full real (итого) | **+13 683** | **+17 256** | Полный pipeline cost на span |

### Экстраполяция на prod-like нагрузку

Ориентир: HTTP-server span ~9 атрибутов, scrubbing enabled, validation/classification enabled.

| Throughput | CPU (full pipeline) | Alloc (full pipeline) |
|------------|--------------------:|----------------------:|
| 10 000 span/s | ~140 ms/s (~0.14 core) | ~178 MB/s |
| 50 000 span/s | ~700 ms/s (~0.7 core) | ~890 MB/s |

H1-only slice (без scrubbing) при 50k span/s: ~100 ms/s CPU, ~145 MB/s alloc.

## Выводы

1. **Обвязка композита пренебрежимо мала** (~216 ns, 720 B/op) — fast-path fan-out не bottleneck.

2. **H1-поверхность (`toSpanData` без scrubbing) — умеренная:** ~2 µs/span, ~1.5 KB alloc.
   Три независимых материализации снимка на `onEnding`. Потенциальный выигрыш от шаринга
   одного `SpanData` между делегатами (ProcessorContext) — порядка **~1.5 KB alloc/span**
   и **~1–2 µs/span**, но это **не доминирующий** cost.

3. **Scrubbing доминирует:** +11.7 µs и +15.7 KB/span сверх H1-slice. Основной CPU —
   regex-движок 12 built-in rules по всем атрибутам; дополнительный 4-й `toSpanData()`.

4. **ProcessorContext refactor — deferred.** Порог из architecture review (>5% на prod-like
   нагрузке) формально не достигнут для H1-slice alone, а scrubbing-оптимизация (если
   понадобится) — отдельная ось, не связанная с шарингом `SpanData`. Решение:
   **не вводить ProcessorContext** до появления prod-метрик, подтверждающих bottleneck.

## Повторный прогон

```bash
# Только CompositePipelineBenchmark (фокус):
./gradlew :platform-tracing-bench:jmh -PjmhInclude=CompositePipelineBenchmark

# Вся JMH-сюита:
./gradlew :platform-tracing-bench:jmh
```

При stale lock после прерывания: остановить висящие `java.exe` (jmh-worker) и удалить
`platform-tracing-bench/build/tmp/jmh/jmh.lock`.

## Ссылки

- `CompositePipelineBenchmark.java` — `platform-tracing-bench/src/jmh/...`
- `PlatformSpanProcessorFactory.java` — production-порядок делегатов
- Architecture review H1 — `tracing_architecture_review_bc2a7817.plan.md`
