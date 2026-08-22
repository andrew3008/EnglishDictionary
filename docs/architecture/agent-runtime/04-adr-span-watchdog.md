# ADR: Span watchdog

Статус: ACCEPTED
Дата: 2026-08-22

## Решение

`SpanWatchdogProcessor` принудительно закрывает зависшие span'ы и трассы. Процессор выключен по умолчанию: включение добавляет фоновый поток и меняет жизненный цикл span'ов у всех потребителей агента. Включается явно `platform.tracing.watchdog.enabled=true`.

Единственный источник правды по счётчикам — JMX-композит `ProcessorWatchdogValues` и его проекции:

- `platform.tracing.processor.watchdog.count{metric=forced_span_closes|forced_trace_closes|tracking_evicted|spans_rejected_per_trace|spans_rejected_global|span_ledger_invariant_violation}`
- `platform.tracing.processor.watchdog.active{scope=spans|traces}`
- `platform.tracing.processor.watchdog.tracked{scope=traces}`
- `platform.tracing.processor.watchdog.capacity{scope=traces|spans|spans_per_trace}`
- секция `runtime.watchdog` в `/actuator/tracingStatus` (схема 5)

Отдельный Micrometer-API в Spring-слое не используется: процессор живёт в classloader'е javaagent-расширения и Micrometer не видит.

Операционные запросы насыщения парные:

```text
spans  saturation = active{scope=spans}   / capacity{scope=spans}
traces saturation = tracked{scope=traces} / capacity{scope=traces}
```

`capacity{scope=spans_per_trace}` публикуется без парного текущего максимума: это fairness guard, а сигналом срабатывания служит `spans_rejected_per_trace`. Отдельного `tracked{scope=spans}` нет: надгробия не содержат span'ов, поэтому он совпал бы с `active{scope=spans}`.

## Свойства

| Свойство | Дефолт | Правило |
| --- | --- | --- |
| `platform.tracing.watchdog.enabled` | `false` | явное включение |
| `platform.tracing.watchdog.span-timeout` | `30s` | `> 0` |
| `platform.tracing.watchdog.trace-timeout` | `60s` | `> 0` и `>= span-timeout` |
| `platform.tracing.watchdog.scan-interval` | `5s` | `> 0` |
| `platform.tracing.watchdog.max-tracked-traces` | `10000` | `> 0`; режима «без лимита» нет |
| `platform.tracing.watchdog.max-tracked-spans-per-trace` | `1024` | `> 0`; режима «без лимита» нет |
| `platform.tracing.watchdog.max-tracked-spans` | `60000` | `> 0`; режима «без лимита» нет |

Бюджеты ортогональны. Правило `max-tracked-spans >= max-tracked-spans-per-trace` не действует: конфигурация `1024 / 500` законна и означает «fairness guard 1024 при абсолютном потолке состояния 500». Если глобальный бюджет ниже per-trace, при старте пишется warn; отказ старта нет.

## Модель состояния

Индекс трасс хранит эпоху трассы по `traceId` и вложенные записи span'ов. Пустая запись не удаляется сразу: она остаётся надгробием с исходным `startNanos` до истечения `trace-timeout`. Возврат span'а с тем же `traceId` внутри окна наследует эпоху.

`activeSpanCount` — точное значение permit-ledger `trackedSpanCount`. `activeTraceCount` считает трассы с хотя бы одним живым span'ом. Полный размер индекса, включая надгробия, публикуется как `trackedTraceCount`.

## Ограничение и вытеснение

Три независимых инварианта закрывают разные failure modes.

1. **Cardinality индекса трасс** — `max-tracked-traces`. При превышении один поток под неблокирующим флагом делает пакетное вытеснение старейших записей по `startNanos`. Граница мягкая: параллельные `onStart` могут превысить лимит на время одного прохода. Вытеснение fail-open: span'ы вытесненной трассы перестают отслеживаться и не закрываются принудительно. Снятие идёт через `computeIfPresent` и `drain()`, поэтому release permit'ов равен точному числу снятых записей. Наблюдаемый сигнал — рост `trackingEvictedTotal`.

2. **Толщина одной трассы** — `max-tracked-spans-per-trace`. Это fairness guard: одна жирная трасса не должна занять весь глобальный бюджет. При исчерпании новые span'ы этой трассы не регистрируются. Уже отслеживаемые продолжают закрываться по таймауту. Запись трассы сохраняется.

3. **Суммарный объём отслеживаемого состояния** — `max-tracked-spans`. Это жёсткая граница JVM-состояния. Допуск идёт через CAS permit-ledger: разные `traceId` не могут одновременно увидеть один свободный слот и оба его занять. При отказе для ранее неизвестного `traceId` запись трассы не создаётся (нет трасс-призраков). Эпоха F4 нужна только уже принятым span'ам.

Реакция на переполнение бюджетов span'ов — отказ в накоплении, а не force-end здоровых span'ов. Watchdog остаётся safety observer и не меняет export semantics приложения: отвергнутый span по-прежнему завершается приложением и экспортируется обычной цепочкой.

## Допуск и ledger

Порядок решения о допуске:

```text
fast-path admissionOpen
    → compute(traceId)
        → authoritative admissionOpen
        → duplicate spanId: no-op
        → per-trace full: spansRejectedPerTraceTotal++
        → tryAcquireSpanPermit CAS
            → отказ, трасса существовала: spansRejectedGlobalTotal++, вернуть state
            → отказ, трасса новая: spansRejectedGlobalTotal++, вернуть null
        → addIfAbsent; при неуспехе release permit
```

Счётчики отказов означают **причину решения о допуске**, а не «сколько раз данный бюджет был насыщен». При одновременном насыщении обоих бюджетов растёт только `spansRejectedPerTraceTotal`. Повторный `onStart` того же `spanId` — идемпотентный no-op до проверок ёмкости и не искажает saturation-счётчики.

`trackedSpanCount` точен посредством lifecycle-операций: успешный допуск `+1` ровно один раз, фактическое снятие `-1` ровно один раз. `scan()` не переписывает ledger. Разница ledger и наблюдаемой суммы под конкуренцией законна (окно между CAS и вставкой/снятием, weakly-consistent обход нескольких карт) и не является дефектом.

`spanLedgerInvariantViolationTotal` растёт только на локально доказанных нарушениях учёта — прежде всего попытке освободить больше permit'ов, чем учтено. В этом случае ledger не уходит ниже нуля: безопаснее получить under-admission, чем открыть memory bound. Код события `PTA-EXPORT-010`, поля `ledgerCurrent`, `requestedRelease`, `operation`, `traceId`. Live-гейт требует ровно ноль.

## Shutdown

Порядок остановки:

```text
admissionOpen = false
остановить планировщик
для каждого traceId: computeIfPresent → drain → releaseExactly → удалить запись
```

Гарантию даёт авторитетная проверка внутри `compute`, а не внешний fast-path. Поток, уже находящийся в `compute` до закрытия допуска, будет дождан и дренирован; поток, входящий после закрытия, не захватывает permit и не вставляет запись. `clear()` со сбросом счётчика не используется.

Незакрытые span'ы при остановке не форсируются повторно. Если индекс ещё содержит записи, пишется warn `Unfinished spans remain at SDK shutdown`.

## Приоритет причин таймаута

Счётчики `forcedSpanCloses` и `forcedTraceCloses` взаимоисключающие. Если одновременно истекли оба таймаута, на span проставляется причина `span` как более конкретная. `forcedTraceCloses` считает закрытия, вызванные исключительно возрастом трассы. Это намеренная семантика, а не дефект наблюдаемости.

## Ёмкость состояния (sizing-гейт)

Исполняемое измерение: `SpanWatchdogSizingGateTest` на карте трасс `WatchdogTraceIndex` (JOL `GraphLayout` после GC, без referent'ов `WeakReference`). Критерии утверждены до прогона:

- retained watchdog state `<= min(32 MiB, 2% эффективного max heap)`;
- `scan` p99 `<= 10%` от `scan-interval` (при дефолте 5 с это 500 мс);
- дефолт `max-tracked-spans` = `min(capacityByHeap, capacityByScanCost)`;
- худший случай heap считается совместно с действующим `max-tracked-traces = 10_000`.

`32 MiB` — абсолютный верхний потолок sizing-гейта, а не безусловный бюджет компонента.

Измерено 2026-08-22, HotSpot 64-bit:

| Объект | Instance size |
| --- | ---: |
| `TraceWatchdogState` | 32 B |
| `Tracked` | 32 B |

| Профиль | Трассы × span'ы | JOL retained | scan p99 |
| --- | --- | ---: | ---: |
| много трасс, мало span'ов | 10 000 × 1 | 2 653 152 B (265 B / запись) | 1.47 мс |
| мало трасс, много span'ов | 1 × 10 000 | 1 613 504 B (161 B / лишний span) | 11 µс |

Модель heap при `T = min(S, 10_000)`: `10_000 × 265 + max(0, S − 10_000) × 161`. Scan линейно ~146 нс/span; `capacityByScanCost ≈ 3.4e6` — не лимитирует. Burst `onStart` p99 = 259 µс; CAS-ledger на 8 потоках закрыл ёмкость 10 000 за 13 мс без livelock.

| Класс heap | Бюджет | `capacityByHeap` |
| --- | ---: | ---: |
| 512 MiB | 10.24 MiB (2%) | 60 232 |
| 1 GiB | 20.48 MiB (2%) | 126 924 |
| 2 GiB и выше | 32 MiB (потолок) | 201 952 |

Провизорные `100_000` span'ов не принимаются как дефолт: на 512 MiB это ~26 MiB retained и нарушает правило 2%. Дефолт фиксируется по самому тесному утверждённому классу (512 MiB) и округляется вниз до тысяч:

- `platform.tracing.watchdog.max-tracked-spans` = **60 000**
- `platform.tracing.watchdog.max-tracked-spans-per-trace` = **1 024** (fairness, не heap-derived; диапазон Elastic 500 … New Relic 3 000)

На больших heap оператор может поднять глобальный лимит до ёмкости своего класса, не превышая 32 MiB retained.

## Внутренняя декомпозиция

Внешний операционный контракт этого ADR не меняется: те же три бюджета, fail-open rejection, надгробия до истечения эпохи, shutdown-порядок, JMX-проекции и sizing-гейт. Меняется только владение кодом.

Весь watchdog, включая публичный `SpanWatchdogProcessor`, живёт в пакете `processor.watchdog`. Цепочка владения одна:

```text
SpanWatchdogProcessor  →  WatchdogTraceIndex  →  TraceWatchdogState
                                              →  SpanPermitLedger
```

| Тип | Владеет | Не владеет |
| --- | --- | --- |
| `SpanWatchdogProcessor` | OTel `SpanProcessor`, планировщик, force-end, public-геттеры | карта трасс, CAS-бюджет |
| `WatchdogTraceIndex` | единственная карта трасс, admit/onEnd/drain/evict/scan-collect, счётчики eviction/rejection | `span.end()`, планировщик |
| `SpanPermitLedger` | CAS-бюджет `trackedSpanCount` и underflow-инвариант | причины отказов, трассы |
| `TraceWatchdogState` | single→multiple promotion/collapse одной трассы | бюджеты и логи |

Мутации `TraceWatchdogState` сериализуются только через per-key `ConcurrentHashMap.compute` / `computeIfPresent` индекса. Фасад не зависит от ledger'а напрямую и не держит `ConcurrentHashMap` / `PriorityQueue` / `AtomicInteger`.

### Identity-safe eviction

Пакетное вытеснение выбирает кандидатов из weakly-consistent снимка `entrySet()`, затем снимает запись через `computeIfPresent`. Между выбором и снятием scan или новый `onStart` могут удалить старый `TraceWatchdogState` и создать новый для того же `traceId` (новая epoch). Удаление только по ключу дренировало бы replacement-состояние.

Кандидат несёт identity ожидаемого состояния: `EvictionCandidate(traceId, expectedState, startNanos)`. Снятие выполняется только при `current == expectedState`. Stale-кандидат не трогает replacement. В `trackingEvictedTotal` и release permit'ов входят только реально удалённые записи. Граница `max-tracked-traces` остаётся eviction-backed soft cap.
