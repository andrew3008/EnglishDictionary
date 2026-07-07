<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## 1. Executive Performance Verdict

Наиболее вероятные bottleneck’и текущего дизайна — не `ArrayDeque` и не `ReentrantLock` сами по себе, а сочетание `toSpanData()` allocation на каждом sampled span, lock contention при многопоточном `onEnd`, и режим деградации, когда exporter медленный и очередь постоянно работает в saturated eviction mode. Самое важное, что **не должно измениться**: `onEnd` обязан оставаться неблокирующим, `exporter.export()` не должен выполняться под producer lock, а критическая секция очереди должна оставаться O(1) с теми же p99‑характеристиками, которые контролируются `QueueOfferBenchmark`.[^1][^2][^3][^4]

Самые безопасные извлечения с точки зрения производительности — **V3 (config/builder extraction)** и **V2 (TimedExporter extraction)**, потому что они почти не трогают горячий путь `onEnd` и не меняют queue/lock choreography. Самые опасные извлечения — **V4/V6**, потому что они вмешиваются в очередь и worker coordination, а также **V7/V8**, потому что они меняют базовый concurrency primitive или вообще архитектуру очереди ради теоретической выгоды, не доказанной текущими benchmark’ами.[^2][^3][^5]

## 2. Current Hot Path Analysis

### Unsampled span path

Для unsampled span горячий путь самый дешёвый: span фильтруется до snapshot materialization и до queue lock, поэтому этот путь почти не участвует ни в allocation pressure, ни в contention pressure. Это важный baseline: любые рефакторинги не должны случайно затянуть сюда лишние allocations, виртуальные вызовы или счётчики.[^3][^2]

### Sampled span path

Для sampled span путь включает `toSpanData()`, затем enqueue под `ReentrantLock`, а затем немедленный return без ожидания export I/O. Это означает, что реальная стоимость горячего пути складывается из трёх частей: snapshot allocation, lock acquisition under contention и O(1) queue mutation/signal.[^4][^3]

### `toSpanData()`

`toSpanData()` вызывается в producer thread и материализует полный immutable snapshot на каждом sampled span; dossier прямо отмечает, что это правильная, но недешёвая операция и под высокой нагрузкой может создавать GC pressure. С практической точки зрения это, вероятно, **главная allocation cost** на hot path, и ни одна queue optimization не уберёт её без нарушения hard constraint “в очередь кладётся `SpanData`, а не live `ReadableSpan`”.[^2][^4]

### Lock acquisition

После snapshot producer захватывает `queueLock`; это самый чувствительный участок для latency tail при 4+ producers, особенно когда worker медленный и очередь часто заполнена. Однако текущая модель держит lock очень коротко и не выполняет под ним exporter I/O, что и делает её performance‑приемлемой.[^1][^3][^2]

### Eviction

При переполнении под тем же lock выполняется `pollFirst()` и атомарно инкрементируется `droppedSpansOverflow`, затем делается `offerLast()` нового span. Это O(1) и кэш‑дружелюбно для `ArrayDeque`; главное performance‑свойство здесь — отсутствие traversal, sorting или allocation в самом eviction шаге.[^3][^2]

### Offer

`offerLast()` для `ArrayDeque` также O(1), а в текущем contract это критично: producer lock hold time должен оставаться микросекундного класса и не зависеть линейно от queue size. Любая замена, которая добавит дополнительную обёртку с defensive copies, boxing или node allocation на span, ухудшит p99 без функциональной выгоды.[^2]

### Signal

Текущий код будит worker через condition signaling; это дёшево по сравнению с exporter I/O, но может усиливать contention в pathological режиме “каждый producer сигналит, worker почти всегда занят”. Тем не менее signal cost всё ещё существенно ниже риска блокировки producer.[^6][^3]

### Return

После unlock producer immediately returns, что и является главным performance invariant. Любой дизайн, где `onEnd` может ждать свободного места в очереди, `Future`, executor submit saturation или synchronized export callback, должен считаться performance regression независимо от теоретической throughput цифры.[^2]

## 3. Contention Model

### Multiple producer threads

Текущая contention модель — many producers / single consumer. Producers конкурируют за один `ReentrantLock`, а worker лишь иногда захватывает тот же lock для batch drain и pending flush list. Это означает, что оптимизация lock hold time даёт больше, чем смена worker threading primitive.[^6][^3]

### Single worker

Один daemon worker — хороший компромисс для maintainability и predictable batching: нет конкуренции consumers, нет reorder ambiguity, и export ordering остаётся проще. С performance точки зрения один worker достаточен, потому что exporter I/O и так внешний bottleneck.[^3][^2]

### Exporter blocked

Когда exporter блокируется или регулярно timeout’ится, queue быстро входит в saturated mode, а producers начинают почти на каждом `onEnd` делать drop‑oldest eviction. В этом режиме основная нагрузка смещается с export throughput на producer contention и overflow accounting, поэтому O(1) eviction under lock становится критически важной.[^7][^4]

### Queue full

На полной очереди producer всё ещё не блокируется; это специально подтверждено test’ом `SP-05T2 onEndDoesNotBlockWhenQueueIsFull`. Следовательно, любая альтернатива очереди должна сохранять именно это свойство, а не просто “уметь работать при переполнении”.[^7][^2]

### forceFlush pressure

`forceFlush` добавляет promises в `pendingFlushes` под тем же lock, что и queue, значит под большим количеством concurrent flush calls contention возрастёт не только между producers, но и между producers и control-plane threads. Это не hot path в нормальной нагрузке, но рефакторинг не должен ухудшить эту shared-lock ситуацию без сильной причины.[^7][^6]

### Shutdown pressure

Во время shutdown worker, producers и terminator thread все начинают касаться общих lifecycle/queue структур; это скорее correctness risk, чем throughput risk, но неправильное решение здесь может увеличить lock hold time и tail latency в shutdown window. Делать shutdown безопаснее можно, но нельзя ради этого удлинять producer path в steady state.[^8][^6]

## 4. Queue Alternatives

| Очередь | Producer latency | Allocation | Drop-oldest semantics | Impl complexity | Testability | Risk |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `ArrayDeque + ReentrantLock` | Низкая при норме, средняя под contention | Низкая в queue layer | Да, естественно | Средняя, уже реализовано | Уже покрыта + JMH | Базовый риск, но known |
| Wrapper над той же реализацией | Почти нейтральна, если inline-friendly | Низкая | Да | Средняя | Лучше изоляция | Средний risk of lock contract bugs |
| `LinkedBlockingDeque` | Может ухудшиться, особенно если ошибочно использовать blocking API | Выше из-за node objects | Не напрямую, нужен custom eviction loop | Средняя-высокая | Средняя | Высокий semantic risk |
| `ArrayBlockingQueue` | Потенциально низкая, но API не подходит под drop-oldest | Низкая | Не напрямую | Высокая из-за адаптации semantics | Средняя | Высокий semantic risk |
| Custom ring buffer | Может быть очень низкой | Очень низкая | Да, если написать правильно | Очень высокая | Низкая initially | Высокий implementation risk |
| LMAX Disruptor | Теоретически низкая latency | Низкая per event | Не нативно под ваш contract | Очень высокая | Низкая для текущей команды/кода | Очень высокий strategic risk |

### Current `ArrayDeque + ReentrantLock`

Это на данный момент **наиболее прагматичная** реализация: contiguous storage, O(1) `pollFirst/offerLast`, отсутствие per-element node allocation, короткая критическая секция и простая drop-oldest semantics. Её слабость — не в raw performance, а в том, что worker/interrupt/shutdown choreography реализованы вручную и хрупки для поддержки.[^4][^6][^3][^2]

### Extracted wrapper around same implementation

Если вынести очередь в `BoundedDropOldestSpanQueue`, но оставить внутри те же `ArrayDeque + ReentrantLock + Condition`, performance может остаться почти нейтральной при условии, что методы маленькие и JIT легко их инлайнит. Основной риск не в CPU cost, а в том, что API между queue и worker может заставить чаще брать lock или добавить лишние object wrappers на batch drain path.[^5][^2]

### `LinkedBlockingDeque`

Эта структура выглядит привлекательно из-за двусторонней очереди, но для hot path она хуже уже тем, что обычно основана на linked nodes, то есть потенциально добавляет allocation churn и pointer chasing. Главное же — её естественные API либо блокируют, либо не дают атомарный drop-oldest exactly the way you need, поэтому всё равно потребуется внешняя синхронизация, и выигрыш над `ArrayDeque` растворяется.[^3]

### `ArrayBlockingQueue`

`ArrayBlockingQueue` лучше по allocation profile, чем `LinkedBlockingDeque`, но семантически это bounded FIFO, а не bounded drop-oldest deque. Чтобы реализовать ваш contract, придётся городить внешний eviction protocol, и в итоге код станет либо сложнее, либо менее быстрым, либо и тем и другим.[^2]

### Custom ring buffer

Теоретически custom ring buffer мог бы дать лучшую producer latency и убрать часть lock overhead, особенно для single consumer. Практически это потребует заново доказывать correctness overflow semantics, shutdown/flush coordination и JMX counters under pressure, а текущий репозиторий не содержит evidence, что `ArrayDeque + ReentrantLock` уже является bottleneck, требующим такого вмешательства.[^1][^3][^2]

### LMAX Disruptor

Disruptor полезен там, где throughput/latency — главная цель и команда готова платить сложностью и новой dependency за особую event-processing модель. Здесь это over-engineering: вам нужен production-maintainable tracing processor, а не микрооптимизированный trading engine queue, причём специфика drop-oldest, forceFlush и shutdown всё равно потребует поверх него немало кастомного кода.[^3][^2]

## 5. Worker Alternatives

| Worker design | Плюсы | Минусы | Verdict |
| :-- | :-- | :-- | :-- |
| Current daemon `Thread` | Простая ownership model, нулевой executor overhead, понятный daemon semantics | Ручной lifecycle/interrupt код хрупкий | Оставить near-term |
| `ExecutorService` single-thread executor | Стандартизирует thread ownership | Не убирает queue/shared-state complexity, добавляет abstraction и shutdown semantics | Не нужен сейчас |
| Virtual thread | Дёшев в создании | Здесь один долгоживущий worker; выгода почти нулевая | Не использовать |
| Scheduled executor | Может упростить delay trigger conceptually | Меняет wait/signal semantics, сложнее координация flush/shutdown | Высокий риск |
| Structured concurrency | Полезна для task scopes | Не подходит для long-lived single worker loop | Неуместно |

### Current daemon Thread

Для одной долгоживущей export loop текущий daemon thread практически оптимален по накладным расходам и предсказуемости. Его проблема — не perf, а brittle exception/interrupt handling.[^2][^3]

### `ExecutorService` single-thread executor

Single-thread executor не делает export быстрее и не уменьшает contention на producer lock. Он может даже ухудшить ясность shutdown semantics, потому что lifecycle расползётся между queue state, executor lifecycle и flush promises.[^2]

### Virtual thread

Virtual threads полезны, когда много краткоживущих блокирующих задач; здесь worker всего один и живёт долго, поэтому practical benefit почти нулевой. Более того, это shiny primitive без business value именно для этого класса.[^3]

### Scheduled executor

С виду он может заменить `awaitNanos(scheduleDelay)`, но фактически вы начнёте координировать timer tasks, flush tasks и shutdown tasks через executor queue, то есть просто перенесёте сложность в другое место. Для performance это спорный обмен.[^6][^7]

### Structured concurrency

Structured concurrency хороша для scoped task trees, а не для долгоживущей очереди с одним фоновым consumer. Для данного компонента она не даёт ни latency win, ни maintainability win.[^3]

## 6. Benchmark Plan

Нужно не один, а набор JMH benchmarks, потому что текущий `QueueOfferBenchmark` уже покрывает steady и saturated producer throughput, но для безопасного рефакторинга нужно измерять ещё несколько режимов.[^1][^2]

### B1. Steady `onEnd`

- **Setup:** sampled spans, exporter быстрый или отключённый background export, queue не заполняется.[^1]
- **Measurement:** throughput и p50/p95/p99 latency на `onEnd` при 1/4/8 producer threads.
- **Success threshold:** в пределах baseline noise; без заметного ухудшения p99.
- **Regression threshold:** любое стабильное ухудшение p99 > 5–10% требует разборки, потому что это hot path.


### B2. Saturated queue eviction

- **Setup:** blocked exporter, маленькая queue, постоянный overflow.[^7][^1]
- **Measurement:** p99 `onEnd`, overflow counter rate, lock contention behavior.
- **Success threshold:** неблокирующее поведение сохраняется, throughput не падает materially versus baseline.
- **Regression threshold:** рост p99 или tail stalls в `onEnd`.


### B3. Exporter blocked

- **Setup:** exporter returns never-completing result, short export timeout.[^7]
- **Measurement:** producer latency, worker cycle throughput, queue saturation rate.
- **Success threshold:** producer path остаётся стабильным и неблокирующим.
- **Regression threshold:** любое backpressure на `onEnd`.


### B4. `forceFlush` pressure

- **Setup:** N threads periodically call `forceFlush()` while producers emit spans.[^7]
- **Measurement:** producer slowdown, flush completion latency, lock contention increase.
- **Success threshold:** producer path degradation limited and bounded.
- **Regression threshold:** large p99 inflation or stuck flushes.


### B5. Shutdown drain

- **Setup:** queue partially full, long schedule delay, close/shutdown under load.[^7]
- **Measurement:** shutdown latency distribution, exported count, producer latency during shutdown window.
- **Success threshold:** no hang, no extreme pause amplification.
- **Regression threshold:** shutdown latency materially higher or nondeterministic.


### B6. Multi-producer contention

- **Setup:** 1, 2, 4, 8, 16 producer threads on sampled spans.[^1]
- **Measurement:** throughput scaling curve and p99.
- **Success threshold:** no unexpected collapse after refactor.
- **Regression threshold:** earlier contention knee than baseline.


### B7. Queue size getter under pressure

- **Setup:** continuous producers + periodic `getQueueSize()` calls from separate thread to model JMX scraping.[^2]
- **Measurement:** overhead of getter polling on producer throughput.
- **Success threshold:** negligible impact.
- **Regression threshold:** visible throughput drop due to lock-heavy getter implementation changes.


## 7. Production Metrics Plan

### Must-have

- `droppedSpansOverflow` and `droppedSpansAfterShutdown`, already present and critical for loss taxonomy.[^4][^2]
- `exportFailures` and `exportTimeouts`, already present and critical for export health correlation.[^4][^2]
- Queue size and capacity via existing JMX getters, because they expose saturation and draining behavior.[^1][^2]


### Nice-to-have

- Worker liveness metric or last successful export timestamp, because current worker death risk is operationally important.[^4][^7]
- Counter for consecutive export failures/timeouts, because one-shot log throttling hides repeated incidents.[^4][^2]
- Histogram or gauge for export batch size, if it can be added without per-span overhead.


### Do not add now

- Per-span timing metrics on `onEnd`, because they would add hot-path overhead precisely where the class is most sensitive.
- Extra per-span allocations for observability wrappers or event objects.
- Fine-grained lock timing instrumentation inside production code; measure it in benchmarks, not in steady-state agent path.


## 8. Recommendation

Самый безопасный performance-neutral рефакторинг — **V3 сначала, затем V2**, или bundled V5 только как последовательность малых PR, а не как один большой diff. Эти шаги почти не трогают hot path и при этом уменьшают размер монолита без необходимости доказывать заново queue latency model.[^5][^3][^2]

Наиболее performance-risky варианты — **V4**, **V6**, **V7** и **V8**. V4/V6 рискуют случайно удлинить lock hold time или добавить abstraction overhead на queue/worker boundary, V7 меняет primitive без гарантий сохранения non-blocking producer semantics, а V8 — явное over-engineering для этой задачи.[^5][^3][^2]

Перед merge обязательно должны быть прогнаны:

- существующий `QueueOfferBenchmark` baseline в steady и saturated modes,[^1][^2]
- новый contention benchmark на 1/4/8 producers,[^1]
- blocked exporter scenario,[^7]
- forceFlush pressure benchmark,[^7]
- shutdown drain benchmark.[^7]

Итоговое инженерное решение: **не менять queue primitive и не вводить shiny concurrency constructs, пока не доказано benchmark’ами, что текущий `ArrayDeque + ReentrantLock` — реальная проблема.** Сейчас evidence показывает обратное: основной риск в этом классе — correctness and maintainability around lifecycle, а не недостаточная теоретическая throughput очереди.[^6][^4][^3][^2][^1]

<div align="center">⁂</div>

[^1]: 03-adjacent-code-map.md

[^2]: 06-refactoring-constraints.md

[^3]: 07-llm-research-input.md

[^4]: 00-executive-summary.md

[^5]: 04-responsibility-decomposition.md

[^6]: 02-concurrency-model.md

[^7]: 05-test-coverage-and-gaps.md

[^8]: 01-current-behavior.md

