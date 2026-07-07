<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## 1. Executive Ranking

Ниже приведён рейтинг по **взвешенному баллу** на шкале 0–100, где 100 — теоретически лучший вариант при заданных весах; это **не то же самое**, что порядок внедрения, потому что реальный rollout всё равно должен начинаться с тестового gate PR-0 и только потом переходить к безопасным шагам.[^1][^2][^3]


| Rank | Variant | Weighted score | Confidence | Recommendation | Suitable phase |
| :-- | --: | --: | :-- | :-- | :-- |
| 1 | V3 — Extract Builder/Config only | 89.1 | High | GO | First structural PR |
| 2 | V5 — Low-risk decomposition bundle | 83.3 | Medium | CAUTION | Near-term target after V3/V2 |
| 3 | V2 — Extract TimedExporter only | 82.6 | High | GO | Second structural PR |
| 4 | V1 — Status quo + targeted fixes | 78.6 | High | GO | Hardening PR / parallel safety work |
| 5 | V6 — Full decomposition | 73.0 | Medium | CAUTION | Long-term target only |
| 6 | V4 — Extract bounded drop-oldest queue | 65.1 | Medium | CAUTION | Later PR only, after tests |
| 7 | V7 — Java concurrency primitive replacement | 52.6 | Low | NO GO | Never |
| 8 | V8 — Ring buffer / Disruptor-style design | 44.3 | Low | NO GO | Never |

Интерпретация рейтинга: V3 выигрывает не потому, что он даёт самую “красивую” архитектуру, а потому, что почти не трогает runtime‑семантику, не меняет lock‑модель, сохраняет Builder API и даёт самый безопасный maintainability win. V5 и V2 высоко стоят за счёт хорошего баланса между maintainability и низким concurrency‑риском, тогда как V1 остаётся сильным operational вариантом, но слабее именно как архитектурное решение, потому что почти не уменьшает монолитность.[^4][^2][^3][^1]

## 2. Scoring Method

Использована взвешенная линейная модель: итоговый балл считается как $\sum(score_i \times weight_i) / 10$, где каждый raw score находится в диапазоне 1–10, а сумма весов равна 100. Такая модель подходит здесь, потому что решение многокритериальное: безопасность поведения, shutdown/forceFlush correctness, maintainability и rollout‑risk тянут в разные стороны.[^3][^1]

Критерии и веса из задачи:

- Behavior preservation — 18
- Concurrency safety — 16
- Shutdown correctness — 12
- ForceFlush correctness — 10
- Maintainability — 12
- Testability — 10
- Performance neutrality — 8
- Observability compatibility — 6
- Incremental deliverability — 5
- Dependency footprint — 3

Смысл критериев в контексте этого класса:

- **Behavior preservation** — насколько вариант сохраняет точные инварианты: drop‑oldest, неблокирующий `onEnd`, `exporter.export()` вне `queueLock`, идемпотентный `shutdown`, `forceFlush` после shutdown как success, JMX getter semantics.[^5][^3]
- **Concurrency safety** — насколько вариант уменьшает или не усиливает R2/R3/R4: early unlock hazard, forceFlush/shutdown race, death worker path.[^2][^6]
- **Shutdown correctness** — насколько вариант помогает безопасно решить зависание `exporter.shutdown()` и не сломать `shutdownResult`/`workerTerminated` semantics.[^6][^5]
- **ForceFlush correctness** — насколько вариант помогает сохранить или усилить гарантии completion для `pendingFlushes` при гонках и одновременных вызовах.[^7][^5][^6]
- **Maintainability** — насколько вариант реально уменьшает девять смешанных ответственностей в монолите на 575 строк.[^4][^2]
- **Testability** — насколько вариант создаёт самостоятельные seams и упрощает unit/characterization testing.[^7][^4]
- **Performance neutrality** — насколько мал риск деградации QueueOfferBenchmark и producer fast path.[^8][^3]
- **Observability compatibility** — насколько легко сохранить шесть JMX‑геттеров, counter semantics и permanent throttle behavior.[^3][^5]
- **Incremental deliverability** — можно ли выкатывать вариант малыми PR с лёгким rollback через сохранение public facade.[^1][^3]
- **Dependency footprint** — требует ли вариант новых библиотек или тяжёлой инфраструктуры сверх текущего стека.[^8][^1]


## 3. Full Scoring Matrix

| Variant | Beh 18 | Con 16 | Shut 12 | Flush 10 | Maint 12 | Test 10 | Perf 8 | Obs 6 | Incr 5 | Dep 3 | Total |
| :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| V1 | 10 | 7 | 9 | 8 | 3 | 5 | 10 | 10 | 10 | 10 | **78.6** |
| V2 | 9 | 8 | 8 | 8 | 7 | 8 | 9 | 9 | 8 | 10 | **82.6** |
| V3 | 10 | 9 | 8 | 8 | 8 | 8 | 10 | 10 | 9 | 10 | **89.1** |
| V4 | 7 | 5 | 6 | 6 | 7 | 7 | 7 | 8 | 5 | 10 | **65.1** |
| V5 | 9 | 8 | 8 | 8 | 8 | 8 | 9 | 9 | 7 | 10 | **83.3** |
| V6 | 6 | 6 | 7 | 7 | 10 | 9 | 8 | 8 | 4 | 10 | **73.0** |
| V7 | 4 | 5 | 5 | 5 | 6 | 6 | 6 | 7 | 3 | 9 | **52.6** |
| V8 | 3 | 4 | 4 | 4 | 7 | 5 | 7 | 6 | 1 | 2 | **44.3** |

Ключевая особенность матрицы: V3 выигрывает за счёт почти идеального профиля по safety‑критериям при хорошем maintainability uplift, а V5 и V2 формируют следующий “безопасный кластер”, потому что не вторгаются в lock ownership и worker/shutdown coupling так глубоко, как V4/V6. V7 и V8 резко проигрывают по behavior preservation и rollout‑risk, потому что либо меняют очередь на другой concurrency primitive, либо полностью меняют threading model и dependency footprint.[^6][^1][^4][^3]

## 4. Score Rationale by Variant

### V1 — Status quo + targeted fixes only

- **Behavior preservation = 10.** Вариант почти не меняет архитектуру и лучше всего сохраняет точные инварианты `enqueueWithDropOldest`, `forceFlush`, `shutdown`, JMX getters и producer fast path.[^5][^3]
- **Concurrency safety = 7.** Он не устраняет structural hazards R2 и R4, но и не вводит новую lock ownership complexity, поэтому риск ниже, чем у V4/V6.[^2][^6]
- **Shutdown correctness = 9.** Это лучший способ локально исправить R1: добавить bounded handling вокруг `exporter.shutdown()` без перестройки остального класса.[^2][^5]
- **ForceFlush correctness = 8.** V1 может точечно закрыть R3, но сама модель `pendingFlushes` под общим queue lock останется сложной и хрупкой для дальнейших изменений.[^6][^7]
- **Maintainability = 3.** Девять ответственностей остаются в одном 575‑строчном монолите, поэтому архитектурный долг почти не уменьшается.[^4][^2]
- **Testability = 5.** Можно дописать characterization tests, но юнит‑изоляция компонентов почти не улучшается.[^7][^4]
- **Performance neutrality = 10.** Producer path и `ArrayDeque + ReentrantLock` не меняются, значит риск регрессии QueueOfferBenchmark минимален.[^3][^8]
- **Observability compatibility = 10.** Геттеры, counter semantics и permanent one‑shot throttle остаются в исходном wire contract виде.[^5][^3]
- **Incremental deliverability = 10.** Это самый маленький и легко обратимый change set.[^3]
- **Dependency footprint = 10.** Новые зависимости не нужны.[^1]

**Biggest hidden risk:** команда может принять V1 за “достаточное решение” и отложить нужную декомпозицию, хотя монолит останется таким же сложным для следующего раунда изменений.[^4][^2]
**Required tests before implementation:** M1, M2, M4, M9 как минимум; M4 особенно важен, потому что именно он экспонирует R1, который V1 должен исправлять.[^7]
**Suitability:** first PR — нет как рефакторинг, но да как hardening; second PR — да; long-term target — нет; never — нет.[^2][^3]

### V2 — Extract TimedExporter only

- **Behavior preservation = 9.** `TimedExporter` изолирует `exportBatch()` и counters без изменения очереди, worker и `pendingFlushes`, поэтому риски ограничены export‑веткой.[^1][^4]
- **Concurrency safety = 8.** Lock‑модель не меняется, потому что export и сейчас вызывается вне `queueLock`; главное — не разделить неправильно ownership counters и failure logging.[^5][^4]
- **Shutdown correctness = 8.** Экстракция сама по себе не решает R1, но и не ухудшает shutdown path, если `exporter.shutdown()` остаётся в основном классе или отдельном shutdown coordinator позже.[^6][^4]
- **ForceFlush correctness = 8.** V2 почти не затрагивает `pendingFlushes`, значит не добавляет новый risk surface для forceFlush race.[^4][^6]
- **Maintainability = 7.** Убирается один из самых чистых seams — timed export, failure classification, `exportTimeouts` и `exportFailures`.[^4]
- **Testability = 8.** `TimedExporter` можно покрывать отдельно через fake exporter и детерминированные timeout/failure сценарии.[^7][^4]
- **Performance neutrality = 9.** Добавляется только indirection на export path, а producer fast path остаётся прежним.[^3]
- **Observability compatibility = 9.** Нужно сохранить couple semantics `exportTimeouts ⊆ exportFailures` и permanent `logExportFailureOnce`; это риск, но он локален и хорошо тестируется.[^3][^4]
- **Incremental deliverability = 8.** Это хороший маленький PR, но чуть сложнее, чем V3, потому что затрагивает runtime counters.[^1][^4]
- **Dependency footprint = 10.** Внешних зависимостей нет.[^1]

**Biggest hidden risk:** разнести `exportFailures`, `exportTimeouts` и `exportFailureLogged` по разным владельцам и тем самым тихо поменять observability semantics.[^5][^3]
**Required tests before implementation:** M6 обязательно; плюс существующий export timeout test и exception isolation test должны пройти без изменения ожиданий.[^8][^7]
**Suitability:** first PR — нет; second PR — да; long-term target — как промежуточный шаг да; never — нет.[^1][^4]

### V3 — Extract Builder/Config only

- **Behavior preservation = 10.** Runtime‑пути `onEnd`, worker, queue, forceFlush и shutdown не меняются, а Builder API и BSP key reading могут быть сохранены буквально.[^5][^3]
- **Concurrency safety = 9.** Вариант почти не касается shared mutable state, поэтому concurrency risk практически нулевой.[^6][^4]
- **Shutdown correctness = 8.** Он не исправляет R1 напрямую, но и не ухудшает shutdown semantics, если `shutdownTimeout` и `exporter.shutdown()` wiring сохраняются.[^8][^5]
- **ForceFlush correctness = 8.** ForceFlush path не затрагивается, значит риск только косвенный через constructor/config wiring, а он невелик.[^4][^5]
- **Maintainability = 8.** Builder validation, fallback logic и `readBspConfigFrom` — отдельная cohesive responsibility, и её извлечение заметно упрощает основной класс.[^2][^4]
- **Testability = 8.** Config/validation становится чисто тестируемым без runtime worker setup, что улучшает reviewability и локальные unit tests.[^7][^4]
- **Performance neutrality = 10.** Producer path и export path не меняются совсем.[^3]
- **Observability compatibility = 10.** Шесть JMX‑геттеров и counter semantics не затрагиваются.[^8][^3]
- **Incremental deliverability = 9.** Это практически идеальный первый структурный PR: малый diff, лёгкий rollback, сохранение public facade.[^4][^3]
- **Dependency footprint = 10.** Новые зависимости не требуются.[^1]

**Biggest hidden risk:** случайно поменять safe fallback semantics Builder’а и превратить production‑safe WARN+fallback в fail‑fast или поменять `Builder.build()` return type/shape.[^5][^3]
**Required tests before implementation:** все builder validation tests, SharedDefaultsAlignmentTest и source‑compat check для public Builder API и BSP key parity.[^8][^7][^3]
**Suitability:** first PR — да; second PR — да, если не был первым; long-term target — как часть bigger plan да; never — нет.[^3][^4]

### V4 — Extract bounded drop-oldest queue

- **Behavior preservation = 7.** Вариант потенциально полезен, но затрагивает самую чувствительную точку: `pollFirst`/`offerLast` under lock, `shutdownRequested` double‑check и queue‑size getters.[^5][^4]
- **Concurrency safety = 5.** R2 живёт ровно на стыке worker и queue lock; extraction C1 без co-design C2 не убирает hazard, а переносит его через class boundary.[^6][^4]
- **Shutdown correctness = 6.** Очередь участвует в timeout‑drain пути terminator thread, поэтому изменение lock ownership может сломать атомарный `droppedSpansAfterShutdown.addAndGet(queue.size()); queue.clear()` path.[^4][^5]
- **ForceFlush correctness = 6.** `pendingFlushes` используют тот же `queueLock`, и любое разделение queue abstraction может нарушить atomic visibility queue + pendingFlushes.[^6][^4]
- **Maintainability = 7.** Если сделать правильно, queue seam ценен, но это не low‑risk seam.[^4]
- **Testability = 7.** Queue станет лучше тестироваться отдельно, но только после того, как будет корректно определён контракт с worker и shutdown.[^7][^4]
- **Performance neutrality = 7.** Есть риск лишних абстракций или изменения lock hold time; JMH gate обязателен.[^8][^3]
- **Observability compatibility = 8.** `droppedSpansOverflow` и `getQueueSize()` всё ещё можно сохранить, но только если ownership counters и lock semantics не расползутся.[^3][^4]
- **Incremental deliverability = 5.** Это уже не маленький PR: он затрагивает queue, worker coupling и shutdown timeout drain path.[^6][^4]
- **Dependency footprint = 10.** Новых библиотек не нужно.[^1]

**Biggest hidden risk:** сломать atomicity eviction+counter или повторно ввести double‑unlock/lock leak через неудачный новый contract между queue и worker.[^6][^3]
**Required tests before implementation:** M1, M2, M7 строго обязательны; SP‑05 tests и QueueOfferBenchmark — обязательный gate.[^8][^7][^3]
**Suitability:** first PR — нет; second PR — нет; long-term target — да, но только поздно; never — нет.[^4]

### V5 — Low-risk decomposition bundle

- **Behavior preservation = 9.** Bundle из config + timed exporter + observability/state может дать крупный выигрыш без прямого вторжения в queue/worker coupling, если оставлять public facade intact.[^1][^3]
- **Concurrency safety = 8.** Он избегает самых опасных зон R2/R3, потому что не вытаскивает queue и worker в первом раунде.[^6][^1]
- **Shutdown correctness = 8.** Не решает всю shutdown архитектуру, но не должен её ухудшать, если shutdown coordinator остаётся прежним.[^5][^4]
- **ForceFlush correctness = 8.** Поскольку `pendingFlushes` и queue lock не трогаются, риск forceFlush‑регрессии умеренный.[^6][^4]
- **Maintainability = 8.** Это лучший near‑term maintainability uplift без опасной concurrency surgery.[^1][^4]
- **Testability = 8.** Появляются отдельные seams для config/export/observability, и основному классу остаются только queue/worker/shutdown concerns.[^4]
- **Performance neutrality = 9.** Producer path всё ещё почти не меняется.[^3]
- **Observability compatibility = 9.** Риск есть только в том, как разнести getter delegation и counter ownership, но он управляемый.[^3][^4]
- **Incremental deliverability = 7.** Как концепция V5 хорош, но как **один PR** он уже крупнее, чем V3 или V2 по отдельности.[^1]
- **Dependency footprint = 10.** Внешних зависимостей не добавляется.[^1]

**Biggest hidden risk:** bundle‑эффект — в одном diff смешиваются сразу несколько перемещений, и review теряет локальность, хотя каждый seam по отдельности безопасен.[^4][^1]
**Required tests before implementation:** весь PR‑0 gate, builder tests, M6, source‑compat getters/builders, QueueOfferBenchmark baseline already fixed.[^7][^8][^3]
**Suitability:** first PR — нет; second PR — возможно, но я бы предпочёл V3→V2; long-term target — да как near‑term architecture state; never — нет.[^1][^4]

### V6 — Full decomposition

- **Behavior preservation = 6.** Вариант архитектурно привлекателен, но одновременно затрагивает queue, worker, shutdown, forceFlush и observability ownership, то есть почти все invariants сразу.[^4][^1]
- **Concurrency safety = 6.** При хорошем дизайне V6 может улучшить модель, но в первом большом проходе он резко увеличивает вероятность сломать R2/R3‑чувствительные участки.[^6][^4]
- **Shutdown correctness = 7.** Можно получить cleaner `ShutdownCoordinator`, но только если правильно выдержать взаимодействие C1/C2/C5 и `workerTerminated`/`shutdownResult` semantics.[^6][^4]
- **ForceFlush correctness = 7.** Аналогично, можно улучшить explicit coordinator, но `pendingFlushes` tightly coupled к queue lock, и это сложный seam.[^6][^4]
- **Maintainability = 10.** Это лучший архитектурный end state по чистоте внутренних boundaries.[^4]
- **Testability = 9.** После успешной реализации почти все responsibilities становятся отдельно тестируемыми.[^7][^4]
- **Performance neutrality = 8.** С careful design performance можно сохранить, но JMH gate обязателен, потому что появляются дополнительные уровни делегации и новые state boundaries.[^8][^3]
- **Observability compatibility = 8.** Публичный facade можно сохранить, но риск неправильного split getter semantics выше, чем у V2/V3/V5.[^3][^4]
- **Incremental deliverability = 4.** Это слишком большой шаг для раннего rollout и плохой кандидат на “один безопасный PR”.[^3][^1]
- **Dependency footprint = 10.** Новых библиотек не требуется.[^1]

**Biggest hidden risk:** full decomposition выглядит “правильной” архитектурой и из‑за этого соблазняет команду сделать слишком большой PR до того, как появятся нужные concurrency characterization tests.[^2][^7]
**Required tests before implementation:** полный PR‑0 gate плюс успешные V3/V2 шаги в main, а для rollout — M1/M2/M3/M4/M7 обязательны.[^7][^3]
**Suitability:** first PR — нет; second PR — нет; long-term target — да; never — нет.[^1][^4]

### V7 — Java concurrency primitive replacement

- **Behavior preservation = 4.** Замена `ArrayDeque + ReentrantLock + Condition` на `LinkedBlockingDeque` или схожий primitive меняет semantics очереди и waiting model.[^6][^1]
- **Concurrency safety = 5.** Она устраняет одну категорию ручного кода, но не даёт drop‑oldest atomicity “из коробки” и может добавить новые race/blocking paths.[^6][^1]
- **Shutdown correctness = 5.** Terminator/worker coordination и bounded drain всё равно придётся реализовывать отдельно; shutdown correctness не становится автоматически лучше.[^5][^6]
- **ForceFlush correctness = 5.** `pendingFlushes` всё равно остаётся отдельно связанным с queue state, так что forceFlush coupling не исчезает.[^4][^6]
- **Maintainability = 6.** Код может выглядеть короче, но не обязательно станет проще семантически, потому что придётся накручивать custom eviction loop поверх стандартной deque.[^1]
- **Testability = 6.** Тестировать стандартный primitive проще, но фактическая логика drop‑oldest+non‑blocking producer останется кастомной.[^7][^1]
- **Performance neutrality = 6.** Producer path и contention profile могут поменяться, а текущая модель уже имеет JMH baseline и O(1) queue ops.[^8][^3]
- **Observability compatibility = 7.** Геттеры можно сохранить, но counter placement и queue size semantics нужно перепроверять.[^3]
- **Incremental deliverability = 3.** Это invasive change в core concurrency model.[^1]
- **Dependency footprint = 9.** Внешних библиотек нет, но появляется dependence на другое поведение JUC primitives, что хоть и не dependency в build sense, но повышает runtime coupling к другой модели.[^1]

**Biggest hidden risk:** accidentally сделать `onEnd` блокирующим или неатомарно реализовать `pollFirst`/`offerLast`, что ломает самый важный invariants set сразу.[^5][^3][^1]
**Required tests before implementation:** весь PR‑0 gate, SP‑05 full suite, JMH saturated mode baseline, explicit no‑blocking producer assertion.[^8][^7][^3]
**Suitability:** first PR — нет; second PR — нет; long-term target — нет; never — да.[^1]

### V8 — Ring buffer / Disruptor-style design

- **Behavior preservation = 3.** Это уже не refactoring текущей модели, а замена архитектуры очереди и threading semantics на другой paradigm.[^1]
- **Concurrency safety = 4.** Теоретически ring buffer может быть быстрым, но practically это большая новая correctness surface без репозиторных доказательств, что она нужна именно здесь.[^2][^1]
- **Shutdown correctness = 4.** Shutdown, flush promises и exporter timeout всё равно придётся проектировать заново поверх новой модели.[^5][^1]
- **ForceFlush correctness = 4.** Current `pendingFlushes` model не переносится естественно в Disruptor design и потребует новой координации.[^6][^1]
- **Maintainability = 7.** После успешной реализации внутренняя queue layer может стать концептуально стройной, но цена входа очень высокая.[^1]
- **Testability = 5.** Появится много новых режимов тестирования и новый класс проблем, а существующие characterization tests не покроют всю новую модель.[^7][^1]
- **Performance neutrality = 7.** Потенциальный upside возможен, но у проекта нет evidence, что текущий benchmark bottleneck требует Disruptor.[^8][^1]
- **Observability compatibility = 6.** JMX facade можно сохранить, но внутренние counters and lifecycle semantics придётся перепроектировать.[^3]
- **Incremental deliverability = 1.** Это почти невозможно выкатывать малыми безопасными PR.[^3][^1]
- **Dependency footprint = 2.** Это единственный вариант, который прямо добавляет новую внешнюю библиотеку и classloader surface.[^1]

**Biggest hidden risk:** команда будет оптимизировать гипотетическую throughput‑проблему, которой репозиторные материалы не доказали, и при этом пожертвует rollback simplicity и correctness confidence.[^2][^8][^1]
**Required tests before implementation:** практически полный redesign test suite плюс новые performance benchmarks; для текущего проекта это неадекватная стоимость входа.[^8][^7]
**Suitability:** first PR — нет; second PR — нет; long-term target — нет; never — да.[^1]

## 5. Sensitivity Analysis

### A. Concurrency-first

Если увеличить приоритет concurrency safety, shutdown correctness и forceFlush correctness, верхняя группа почти не меняется: **V3 > V5 > V2 > V1**, потому что V3/V5/V2 не влезают рано в queue/worker coupling, а V1 хотя и безопасен, не уменьшает R2 structural hazard. V4 и V6 при таком профиле не растут, потому что именно они сильнее всего касаются опасного участка early unlock и shared queue lock ownership.[^4][^6][^1]

### B. Maintainability-first

Если приоритетом становится maintainability, растут **V5** и **V6**, потому что они сильнее дробят девять responsibilities и создают более чистые seams. Однако даже в этом профиле recommendation не должна переключаться на V6 как первый шаг, потому что rollout‑risk и test gate остаются слишком тяжёлыми для production‑critical path.[^2][^7][^3][^4]

Ожидаемый порядок: **V5 ≈ V3 > V6 > V2 > V1 > V4 > V7 > V8**.[^4][^1]

### C. Simplicity-first

Если важнее всего маленький diff, простой rollback и минимум moving parts, выигрывают **V3** и **V1**. V3 остаётся лучшим refactoring‑первым шагом, а V1 — лучшим non‑structural hardening path.[^2][^3][^4][^1]

Ожидаемый порядок: **V3 > V1 > V2 > V5 > V4 > V6 > V7 > V8**.[^3][^1]

### D. Performance-first

Если увеличить вес performance neutrality, растут **V1** и **V3**, потому что они почти не трогают producer fast path и текущую `ArrayDeque + ReentrantLock` модель, уже контролируемую QueueOfferBenchmark. V7 и V8 не получают автоматического бонуса, потому что в репозитории нет доказательств, что смена primitive или Disruptor даст лучший p99 именно для этого класса.[^8][^3][^1]

Ожидаемый порядок: **V3 > V1 > V5 > V2 > V6 > V4 > V7 > V8**.[^8][^1]

### E. Lowest-risk rollout-first

Если главным становится безопасный поэтапный rollout, картина становится ещё более консервативной: **V3 > V1 > V2 > V5**, а всё, что трогает queue/worker, откладывается дальше. Это наиболее реалистичный профиль для production‑critical telemetry infrastructure.[^2][^7][^8][^3]

Ожидаемый порядок: **V3 > V1 > V2 > V5 > V4 > V6 > V7 > V8**.[^3][^1]

**Итог sensitivity analysis:** recommendation не меняется в главном. Первый безопасный refactoring step остаётся V3, near‑term decomposition path — V3→V2→V5, а V6 остаётся только долгосрочной целью после test gate и малых успешных extraction steps.[^7][^4][^1]

## 6. Dominated Options

**V4 dominated by V3.** V3 лучше или равен V4 по всем критериям в этой матрице и строго лучше по behavior preservation, concurrency safety, performance neutrality, observability compatibility, incremental deliverability и dependency footprint; при этом V4 лезет в более опасную queue/worker зону.[^6][^4][^3]

**V7 dominated by V4 and V3.** Даже относительно рискованного V4, вариант V7 не даёт лучшей behavior semantics, не улучшает incremental deliverability и вводит больше шансов сломать non‑blocking `onEnd`; на фоне V3 он проигрывает почти везде.[^3][^6][^1]

**V8 dominated by V6 and V3.** V6 даёт лучше maintainability/testability без новой dependency и без полной смены queue paradigm, а V3 даёт намного лучший safety profile при практически нулевой цене внедрения.[^4][^3][^1]

**Недоминруемые варианты:** V1, V2, V3, V5, V6. Это и есть реальное decision set: V1 силён по rollout simplicity и performance neutrality, V2/V3/V5 — по safe decomposition, V6 — по long‑term maintainability ceiling.[^3][^1]

## 7. Pareto Frontier

На Pareto frontier находятся **V1, V2, V3, V5, V6**: ни один из них нельзя исключить только по одному числу, потому что каждый оптимален хотя бы в одном trade‑off измерении.[^4][^1]

- **Safest near-term refactoring:** **V3**, потому что он почти не влияет на runtime semantics, но уже уменьшает монолитность и улучшает reviewability.[^4][^3]
- **Safest near-term hardening:** **V1**, потому что он позволяет закрыть R1/R3 без широкой перестройки класса.[^2][^6]
- **Best maintainability / risk balance:** **V5**, если делать его как последовательность малых PR, а не как один bundle diff.[^4][^1]
- **Best long-term architecture:** **V6**, но только после прохождения через V3/V2/V5 и полного test gate, иначе риск слишком высок.[^7][^4]
- **Best testability uplift per unit of risk:** **V2** и **V3**.[^7][^4]
- **Lowest dependency risk:** все варианты, кроме **V8**, равны по этому критерию; V8 единственный явно ухудшает dependency footprint.[^1]


## 8. Final Decision Recommendation

**Recommended first PR:** не архитектурный вариант, а **PR-0 test gate**: M1, M2, M3, M4, M6, M7, M9 плюс фиксированный QueueOfferBenchmark baseline и source‑compat tests для Builder и шести JMX‑геттеров.[^2][^7][^3]
**Recommended first structural PR:** **V3** — Extract Builder/Config only.[^3][^4]
**Recommended target architecture:** **near-term target = V5 as staged sequence (V3 → V2 → small observability/state cleanup)**, **long-term target = V6 only if additional decomposition still pays off after those steps**.[^4][^1]

**Variants to reject:** **V7** и **V8**. V7 слишком легко нарушает non‑blocking producer contract и не даёт готового drop‑oldest without custom synchronization, а V8 добавляет новую dependency и по сути превращает рефакторинг в redesign.[^3][^1]

**Minimum test gate before any implementation:**

- M1 concurrent forceFlush + shutdown race.[^7]
- M2 worker death from unchecked exception.[^7]
- M4 exporter.shutdown hang / bounded termination behavior.[^2][^7]
- M6 logExportFailureOnce throttle verification.[^7]
- M7 interrupt path isolation.[^6][^7]
- Source compatibility tests for Builder API and six JMX getters.[^8][^3]
- QueueOfferBenchmark baseline with no material regression in steady and saturated modes.[^8][^3]

**Architectural decision statement:**
Для `PlatformDropOldestExportSpanProcessor` следует выбрать **консервативный staged refactoring path**: сначала зафиксировать behavior и concurrency invariants тестами, затем выполнить **V3** как первый structural extraction, затем **V2**, затем прийти к **V5** как безопасному near‑term decomposition state; **V6** допустим только как долгосрочная цель после стабилизации и только при сохранении public facade, JMX wire contract, Builder API, drop‑oldest semantics и non‑blocking producer path.[^3][^4][^1]

<div align="center">⁂</div>

[^1]: 07-llm-research-input.md

[^2]: 00-executive-summary.md

[^3]: 06-refactoring-constraints.md

[^4]: 04-responsibility-decomposition.md

[^5]: 01-current-behavior.md

[^6]: 02-concurrency-model.md

[^7]: 05-test-coverage-and-gaps.md

[^8]: 03-adjacent-code-map.md

