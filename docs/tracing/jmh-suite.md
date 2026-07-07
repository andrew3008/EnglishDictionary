# JMH-сюита platform-tracing-bench (Фаза 17, PR-1)

> 15 классов: 14 измерительных + общие fixtures (`ScrubbingFixtures`).

> Граница ответственности (ADR-performance-model, Решение 5): JMH детектирует локальные
> регрессии и компонентную alloc-стоимость; release-approval требует macro-прогонов
> M0/M5/M6/M8/M9/S1 (см. [performance-test-matrix.md](performance-test-matrix.md)).

## Состав сюиты (14 классов)

| Класс | Что измеряет | Требование / инвариант |
|-------|--------------|-------------------------|
| `StartSpanBenchmark` | baseline `startSpan+close` через фасад | вход микробюджета span.start/end |
| `TracedAspectBenchmark` | цикл `@Traced` (без Spring proxy) | — |
| `CompositePipelineBenchmark` | H1: композит, `toSpanData()`, scrubbing в составе pipeline | REQ-DATA-001; [h1-baseline](h1-composite-pipeline-jmh-baseline.md) |
| `CompositeSamplerBenchmark` | ветки force-header / parent / ratio-drop (пустая политика); `@Threads(1/8)`; **avgt+SampleTime** | REQ-SAMPLING-001, P5 |
| `CompositeSamplerPolicyBranchesBenchmark` | dropPath / routeRatio sample+drop / full-chain под заполненной политикой; `@Threads(1/8)` | ADR-runtime-sampling-policy C-3, P5 |
| `CompositeSamplerConcurrentUpdateBenchmark` | `@Group` 7 readers + 1 writer (CAS-шторм апдейтов): чтение не стопорится reload'ами | ADR-runtime-sampling-policy C-2/C-4/C-7; macro-аналог M10c |
| `ScrubbingEngineBenchmark` | full-set: clean-keys, JWT-hit, long-value | REQ-DATA-001, P4; данные для ADR-scrubbing-cost |
| `ScrubbingPerRuleBenchmark` | per-rule hit (`@Param` 12 правил) — ранжирование стоимости | данные для ADR-scrubbing-cost |
| `TypedBuilderBenchmark` | фасад vs typed builder (DISABLED/WARN) | Фаза 13 semantic layer |
| `AttributePolicyBenchmark` | `validateAndNormalize`: WARN valid / WARN violations / DISABLED | — |
| `QueueOfferBenchmark` | `span.end()`→offer: BSP vs drop-oldest, steady vs saturated; `@Threads(4)`; **avgt+SampleTime** | REQ-EXPORT-ASYNC-001, P1/P2 |
| `ContextScopeBenchmark` | `Context.current()`, вложенные `makeCurrent()` (1/3/10) | скрытая статья hot-path |
| `MdcCorrelationBenchmark` | MDC put/remove тройки + `RemoteServiceMdc` цикл | кандидат M9 soak |
| `SpanLimitsBenchmark` | лимиты 50/1000/10: на лимите и сверх | REQ-DATA-001 §2.1–2.3 |
| `HeaderPropagationBenchmark` | W3C + platform inject/extract (вкл. gate-проверку) | REQ-SAMPLING-001 |

## Методология

- Режимы и единицы задаются **аннотациями классов**, не глобально: latency-чувствительные
  бенчи (sampler, queue offer) дополнительно гоняют `SampleTime` для p50/p95/p99.
- Alloc-метрика — только `gc.alloc.rate.norm` (байт/op); метрика без `.norm`
  имеет известное искажение GC-профайлера JMH.
- `fork=2`, `5×5` итераций; результаты — JSON (`build/results/jmh/results.json`).
- Контракт fixtures: `ScrubbingBenchmarkFixtureContractTest` гарантирует, что per-rule
  сценарии действительно матчатся своими правилами (hit-путь), а clean-сценарии — нет.

## Workflow baseline

```bash
# Полный прогон сюиты (или -PjmhInclude=<Class> для одного класса)
./gradlew :platform-tracing-bench:jmh

# Зафиксировать текущий прогон как baseline (per hardware-profile)
./gradlew :platform-tracing-bench:jmhSaveBaseline

# Сравнить новый прогон с baseline
./gradlew :platform-tracing-bench:jmh
./gradlew :platform-tracing-bench:jmhCompareBaseline            # report-only
./gradlew :platform-tracing-bench:jmhCompareBaseline -PjmhFailOnRegression  # strict hard gate
```

### Gate policy (PR-6C3)

| Metric | Mode | WARN | FAIL | Blocks `-PjmhFailOnRegression` |
|--------|------|------|------|--------------------------------|
| Latency | `avgt` | +10% | +25% | **yes** (hard gate) |
| `gc.alloc.rate.norm` | all | +5% | +15% | **yes** (hard gate) |
| Latency | `sample` (SampleTime tail) | +10% | +25% | **no** (diagnostic by default) |

- Sample-mode latency entries are **compared and reported** as `JMH DIAG WARN` / `JMH DIAG FAIL`.
- Release confidence for tail latency (p99/p999) comes from **macro-load HDR/JFR evidence**
  (M0/M5/M6/M8/M9/S1), not JMH sample mode alone — see ADR-performance-model.
- Optional strict sample gate for investigation:
  `./gradlew :platform-tracing-bench:jmhCompareBaseline -PjmhFailOnRegression -PjmhFailOnSampleRegression=true`
- Summary separates: `hardWARN`, `hardFAIL`, `diagWARN`, `diagFAIL`, `NEW`, `matched`.
- If `matched=0` with non-empty files, gate is **unreliable** and fails under `-PjmhFailOnRegression`.

- Baseline хранится в `platform-tracing-bench/baselines/<hardwareProfileId>/`
  (`results.json` + `baseline-metadata.json`: gitSha, JDK, ОС, ядра) и коммитится.
- Сравнения валидны **только same-profile**; абсолютные ns/op между хостами не переносимы.
- Официальные цифры — только с reference-лаборатории (см.
  [performance-budgets.yaml](performance-budgets.yaml) `referenceLab`); Windows-хосты —
  smoke-tier (риск R2 ADR-performance-model).
- `-PjmhQuickSmoke` — проверка работоспособности сюиты (1×1×100ms), НЕ для измерений
  и НЕ для baseline.
