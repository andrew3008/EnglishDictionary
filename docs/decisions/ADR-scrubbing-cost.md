# ADR: Scrubbing cost — decision tree и профили конфигурации

| Поле | Значение |
|------|----------|
| Статус | **Accepted** (числа — indicative, пересчёт на reference-лаборатории по PR-3) |
| Дата | 2026-06-10 |
| Связанные документы | [ADR-performance-model.md](ADR-performance-model.md) (открытый вопрос 6), [jmh-suite.md](../tracing/jmh-suite.md), [h1-composite-pipeline-jmh-baseline.md](../tracing/h1-composite-pipeline-jmh-baseline.md) |
| Источник данных | `ScrubbingEngineBenchmark` + `ScrubbingPerRuleBenchmark`, dev-хост `windows-10-amd64-20c`, профиль `-PjmhDev` (1 fork × 3+5 итераций) |

## Контекст

Wave B (H1 baseline) установила: **scrubbing — доминирующая статья расхода span-pipeline**
(+11.7 µs / +15.7 KB на prod-like span сверх стоимости `toSpanData()`-слоя). Требование
§2.4 (masking чувствительных данных) — обязательное; вопрос не «выключать ли scrubbing»,
а «где его честная цена и какие конфигурации предлагать интеграторам».

## Данные (indicative, dev-хост)

### Full-set сценарии (12 default-правил, `MergeEngine.evaluate` на один атрибут)

| Сценарий | ns/op | Интерпретация |
|----------|-------|----------------|
| `jwtHitFullSet` (hit по приоритетному правилу) | ~114 | first-match early-exit работает: hit дешевле полного прохода |
| `cleanKeysFullSet` (ничего не матчится) | ~873 | **hot-path большинства атрибутов**: полный проход 12 правил ≈ 0.9 µs/атрибут; на 9-атрибутном span ≈ 8 µs — согласуется с Wave B |
| `longValueCleanFullSet` (clean-значение ~2000 chars) | **~56 600** | **×65 от короткого clean**: value-based правила сканируют длинные строки целиком |

### Per-rule hit (одно правило, матчащееся значение)

| Правило | ns/op | B/op | Тип |
|---------|-------|------|-----|
| `jwt` | ~436 | — | value-regex (3 сегмента base64) |
| `email` | ~131 | 240 | value-regex |
| `oauth-header` | ~108 | — | key + value-префикс |
| `x-auth-header` | ~104 | — | key-based |
| `user-identity` | ~85 | 136 | key-based (2 группы токенов) |
| `ip-address` | ~70 | 120 | value-regex |
| `infra-credential` | ~63 | 160 | key-based |
| `ssh-credential` | ~62 | 152 | key-based |
| `hardware-identity` | ~56 | 120 | key-based |
| `webhook-token` | ~54 | 152 | key-based |
| `location` | ~54 | 120 | key-based |
| `password` | ~48 | 152 | key-based |

## Выводы

1. **Key-based правила дёшевы** (~50–105 ns): отсечение по ключу до regex — работающий
   паттерн, менять их не нужно.
2. **Value-based правила (jwt, email, ip-address) — главная статья расхода** на clean-path:
   они вынуждены сканировать значение каждого строкового атрибута. Именно они дают
   ~873 ns clean-прохода и катастрофические ~57 µs на длинных значениях.
3. **Длина значения — главный усилитель риска** (×65). SpanLimits обрезает значения до
   1000 chars ДО процессора, поэтому верхняя граница в проде ограничена, но 1000-char
   значения × N атрибутов × value-regex — всё ещё десятки µs на span.

## Decision tree

```text
Атрибут (key, value) →
├─ value не String / короче минимальной длины секрета → KEEP без regex   [уже так]
├─ key матчит key-based правило (50–105 ns)           → действие правила  [уже так]
└─ value-based правила (jwt/email/ip):
   ├─ длина value ≤ scan-cap → полный regex-скан
   └─ длина value > scan-cap → сканировать только префикс scan-cap
      (секреты в виде целого значения попадают в префикс; гарантия P4:
       время скана bounded независимо от входа)
```

## Решения

1. **Default-набор правил не сокращаем.** Pre-production решение обязано быть
   secure-by-default; 0.9 µs/атрибут на clean-path — приемлемая цена в составе бюджета M5
   (подтверждается macro-прогоном PR-3).
2. **Scan-cap для value-based правил — backlog-кандидат №1** (НЕ реализуется в Фазе 17:
   фаза доказывает модель, а не переписывает движок). Evidence: ×65 на 2000-char clean.
   Решение о реализации — board, после M5: если scrubbing-вклад укладывается в CPU < 3% —
   оптимизация не обязательна.
3. **Профили конфигурации** (`platform.tracing.scrubbing.built-in-rules` уже позволяет
   задавать список):
   - **strict-default** (рекомендация, текущее поведение): все 12 правил;
   - **performance-default**: 7 critical key-based правил + `jwt`; исключаются value-regex
     `email`/`ip-address` и key-based `location`/`hardware-identity`/`user-identity` —
     для сервисов, где эти ключи не появляются (экономия ~30–40% clean-прохода).
     Выбор дефолта — открытый вопрос 6 ADR-performance-model (board).
4. **Shared SpanData между процессорами — explicit non-goal.** H1 baseline показал, что
   `toSpanData()`-налог вторичен относительно scrubbing; рефакторинг `ProcessorContext`
   не окупается (анти-over-engineering, вердикт h1-baseline).
5. **Пересчёт на reference-лаборатории** обязателен перед sign-off: текущие числа —
   ранжирование и порядки величин, не официальные цифры (риск R2).

## Последствия

- `ScrubbingPerRuleBenchmark` остаётся в сюите как регрессионный детектор стоимости правил
  (новое правило обязано добавить fixture и попасть в ранжирование).
- Контракт fixtures охраняется `ScrubbingBenchmarkFixtureContractTest` — изменение regex'а
  правила, ломающее hit-путь бенчмарка, упадёт в обычном `test`.
- В runbook PR-5 добавляется ветка диагностики «высокий CPU → проверь долю длинных
  строковых атрибутов и вклад value-based правил».
