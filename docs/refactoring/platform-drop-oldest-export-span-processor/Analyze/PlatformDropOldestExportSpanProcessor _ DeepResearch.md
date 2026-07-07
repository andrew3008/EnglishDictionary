# Архитектурный отчёт по рефакторингу PlatformDropOldestExportSpanProcessor

## 1. Executive Summary

Рекомендуемый ближнесрочный путь: вариант **V5 (Low-risk decomposition bundle)** с предварительной фазой характеризационных тестов (Phase 0) и минимальными структурными извлечениями без изменения цикла воркера и очереди.[^1][^2][^3]

Рекомендуемая долгосрочная архитектура: **V6 (Full internal decomposition)** на базе компонентов C1–C8 из responsibility decomposition, но только после стабилизации тестов M1–M9, повторного JMH-бенчмарка и проверки, что извлечения V3 и V2 не изменили инварианты.[^4][^1]

Варианты к явному отклонению сейчас: **V7 (замена на BlockingDeque/ExecutorService)** и **V8 (Disruptor/ring buffer)** ввиду высокого риска смещения семантики drop-oldest, блокирующего `offer`, изменения профиля задержек `onEnd` и роста зависимостей, которые сложно обосновать для данной платформы.[^5][^6][^4]

Топ‑5 production‑рисков исходного дизайна:
- R1: `exporter.shutdown()` без таймаута — возможный вечный блок `SdkTracerProvider.shutdown()` по цепочке `shutdownResult`.[^2][^7]
- R2: нестандартный паттерн `queueLock.unlock()` в `InterruptedException` + `isHeldByCurrentThread` в `finally` — высокая опасность двойного unlock или leak при любом рефакторинге блока.[^7]
- R3: потеря промисов `forceFlush` при гонке с shutdown (worker покинул цикл до обработки `pendingFlushes`).[^7]
- R4: смерть воркера при непойманном RuntimeException в `workerLoop` без восстановления — полная остановка экспорта.[^2][^7]
- R6: перманентный throttle `logExportFailureOnce` — последующие ошибки экспорта не видны в логах, только по счётчикам.[^8][^2]

Рефакторинг начинать можно, **только** выполнив Phase 0: добавить характеризационные тесты M1–M9, зафиксировать JMH‑базу QueueOfferBenchmark и прогнать все существующие тесты; без этого любая декомпозиция очереди/воркера будет чрезмерно рискованной.[^3][^2][^7]

## 2. Evidence and Assumptions

### Repository evidence used

- `00-executive-summary.md`: обзор класса, топ‑риски, список вспомогательных файлов.[^2]
- `01-current-behavior.md`: детальное поведение очереди, воркера, forceFlush, shutdown, билдера и счётчиков.[^8]
- `02-concurrency-model.md`: потоки, защищённые поля, состояние воркера, анализ паттерна `awaitNanos` + явного unlock.[^7]
- `03-adjacent-code-map.md`: фабрики, JMX‑регистратор, конфиги, ADR‑ссылки, тесты и бенчмарки вокруг процессора.[^9]
- `04-responsibility-decomposition.md`: анализ R1–R9, кандидатные компоненты C1–C8, рекомендуемый порядок извлечения.[^1]
- `05-test-coverage-and-gaps.md`: существующие тесты по группам и недостающие M1–M9.[^10]
- `06-refactoring-constraints.md`: жёсткие API‑, контрактные, наблюдаемостные, производительные и rollback‑ограничения.[^3]
- `07-llm-research-input.md`: формулировка восьми вариантов V1–V8, краткие риски и критерии оценки.[^4]

Исходный Java‑файл `PlatformDropOldestExportSpanProcessor.java` описан через ссылки на строки во всех md‑файлах; прямого доступа к нему нет, поэтому отчёт опирается на цитируемые фрагменты и ADR.[^8][^7]

### External sources used

- OpenTelemetry Java `SpanProcessor` и его lifecycle: Javadoc и GitHub исходники.[^11][^12]
- OpenTelemetry Java `BatchSpanProcessor`: очередь, drop нового при переполнении.[^13]
- OpenTelemetry Java `SpanExporter` контракт (`export`, `flush`, `shutdown`, `CompletableResultCode`).[^14]
- `CompletableResultCode` и поведение `join(timeout, unit)` (обсуждение таймаутов и fail на timeout).[^15]
- JDK Condition/Lock/awaitNanos semantics, включая спуриюс wakeups и возвращаемое «остаточное» время.[^16][^17][^18]
- JDK `BlockingDeque` API и семантика методов `offerLast`, `pollFirst`.[^5]
- Java 21 virtual threads и structured concurrency: влияние на блокировки и профили нагрузки.[^19][^20]
- LMAX Disruptor User Guide и Javadoc по `RingBuffer`: модель один/много продюсеров, WaitStrategy, сложность интеграции.[^6][^21]

### Assumptions

- Версия OTel SDK в платформе близка к 1.x, где `SpanProcessor.shutdown()`/`forceFlush()` уже возвращают `CompletableResultCode`, а `BatchSpanProcessor` реализует drop‑new (при full queue просто отбрасывает новые спаны).[^13][^4]
- Ссылки на строки в md‑файлах точно соответствуют текущей версии Java‑класса в репозитории; расхождение не рассматривается.[^8][^7]
- ADR‑документы (`ADR-drop-oldest-export-processor-v1.md`, `ADR-bsp-overflow-policy-finding.md`) отражают актуальную контрактную семантику (drop‑oldest, JMX counters, rollback через env‑var).[^9][^2]
- Производительный профиль QueueOfferBenchmark (steady vs saturated) по‑прежнему релевантен; допущено, что другие части пайплайна не изменились.[^9][^3]

### Evidence gaps

- Нет прямого текста `PlatformDropOldestExportSpanProcessor.java`, поэтому детали имплементации, не описанные в md‑файлах, не могут быть проверены; такие аспекты отмечаются как «Evidence missing».
- Нет явной версии OTel SDK для Java (1.23, 1.30 и т.д.), поэтому сравнение с `BatchSpanProcessor` и `SpanProcessor` опирается на 1.0–1.16 Javadoc, что потенциально расходится с локальной версией; в случае конфликта приоритет за ADR/репозиторием.[^12][^13]
- Реальная конфигурация exporter (OTLP, Zipkin и т.п.) не дана; оценки риска shutdown‑hang делаются абстрактно.

## 3. Current Design Diagnosis

### Responsibility overload

Класс объединяет девять разных ролей: bounded drop‑oldest очередь (ArrayDeque + ReentrantLock + Condition), экспортный worker, тайм‑аутный экспорт, forceFlush‑координацию, двухфазный shutdown, билдер/валидацию/чтение BSP‑ключей, наблюдаемостные счётчики и lifecycle guard на базе `AtomicBoolean shutdownRequested`.[^1][^2]

Эти ответственности тесно переплетены: очередь и `pendingFlushes` используют общий `queueLock`, worker напрямую манипулирует этим lock в `InterruptedException` пути, shutdown‑terminator одновременно трогает latch, exporter и очередь, а билдер и JMX‑getters живут в том же файле без явных внутренних границ.[^1][^7]

### Concurrency model

Модель потоков:
- Продюсеры: пользовательские application threads, вызывающие `onEnd`, материализуют `SpanData` и заходят в критическую секцию `enqueueWithDropOldest` под `queueLock`.[^7][^8]
- Worker: одиночный daemon `platform-tracing-drop-oldest-exporter`, созданный конструктором и сразу запущенный; он ждет на `queueNotEmpty.awaitNanos`, проверяет `shouldExportNow`, дренирует batch и pending flushes под lock, затем отпускает lock и вызывает `exporter.export`.[^8][^7]
- Terminator: отдельный daemon `platform-tracing-drop-oldest-shutdown`, запускаемый при `shutdown()`; ждёт `workerTerminated` latch, по таймауту прерывает воркера и чистит очередь, затем вызывает `exporter.shutdown()`.[^7][^8]

Lock‑картина: единственный `ReentrantLock queueLock` защищает `queue` и `pendingFlushes`; Condition `queueNotEmpty` используется для ожидания бэтча/таймера/forceFlush/shutdown. Все операции `queue` и `pendingFlushes` выполняются под этим lock, а `exporter.export()` всегда вызывается с уже отпущенным lock, что сохраняет неблокирующий `onEnd`.[^8][^7]

Самый опасный фрагмент — обработка `InterruptedException` внутри worker: при прерывании `awaitNanos` поток ре‑устанавливает флаг прерывания, помечает `shuttingDown`, дренирует batch и pending flushes, затем **явно** вызывает `queueLock.unlock()`, после чего выносит export — а в `finally` стоит `if (queueLock.isHeldByCurrentThread) queueLock.unlock()` для защиты от double‑unlock. Любое изменение структуры try/catch/finally рискует нарушить этот инвариант.[^7]

### Lifecycle model

Жизненный цикл процессора:
- Конструктор копирует параметры билдера, создаёт очередь фиксированного размера и сразу стартует daemon worker.[^8]
- `onStart` реализован для удовлетворения абстрактного метода SpanProcessor, но фактически является no‑op при `isStartRequired=false`; экспорт работает только на `onEnd`.[^12][^8]
- `onEnd` проверяет sampling (`!isSampled → return`), быстрый путь `shutdownRequested.get()` (увеличение `droppedSpansAfterShutdown` и возврат) и затем `toSpanData()` с обработкой RuntimeException через `logExportFailureOnce`. Успешный snapshot отправляется в `enqueueWithDropOldest`, где реализуется eviction `pollFirst` + `offerLast` и инкремент `droppedSpansOverflow`.[^8]
- Worker в `workerLoop` ждёт `shouldExportNow` по нескольким триггерам (batch size, non‑empty + scheduleDelay, pending flushes, shutdownRequested), затем дренирует batch и pending flushes, делает export и по необходимости повторно дренирует очередь при shuttingDown=true.[^7][^8]

### Shutdown model

Shutdown имеет две фазы:
- Фаза 1 (caller): `shutdown()` делает `compareAndSet(false,true)` на `shutdownRequested`, при втором вызове возвращает сохранённый `shutdownResult` (идемпотентность). Под `queueLock` вызывает `queueNotEmpty.signalAll` и немедленно возвращает `shutdownResult` (non‑blocking для вызывающего потока).[^3][^8]
- Фаза 2 (terminator): создаёт daemon terminator, который ждёт `workerTerminated.await(shutdownTimeoutNanos)`. При таймауте логирует WARN, вызывает `workerThread.interrupt()`, затем под lock увеличивает `droppedSpansAfterShutdown` на `queue.size()` и очищает очередь. После этого вызывает `exporter.shutdown()` **без таймаута**, а его `CompletableResultCode` через `whenComplete` завершает `shutdownResult`.[^7][^8]

Если `exporter.shutdown()` никогда не завершится, terminator останется висеть, `shutdownResult` не завершится, и `SdkTracerProvider.shutdown()` может заблокироваться навсегда по цепочке ожидания всех SpanProcessor shutdown‑кодов.[^14][^12][^2]

### forceFlush model

`forceFlush()`:
- Если `shutdownRequested.get()` уже true, сразу возвращает `CompletableResultCode.ofSuccess()` без каких‑либо действий.[^8]
- Иначе создаёт новый `CompletableResultCode result`, под `queueLock` добавляет его в `pendingFlushes` и вызывает `queueNotEmpty.signalAll`, возвращая `result` вызывающему коду.[^7][^8]
- Worker при следующем проходе, обнаружив непустой `pendingFlushes`, дренирует batch и `drainPendingFlushesLocked`, затем после export вызывает `promise.succeed()` или `promise.fail()` для всех промисов.[^7]

Гонка с shutdown: если shutdown устанавливает флаг и побуждает воркера выйти из цикла до обработки нового promise, вызывающий может получить `CompletableResultCode`, который **никогда не завершится**, что нарушает ожидаемую семантику `forceFlush` и может блокировать `SdkTracerProvider.forceFlush()`.[^12][^7]

### Observability model

Наблюдаемость предоставляет:
- AtomicLong `droppedSpansOverflow`, `droppedSpansAfterShutdown`, `exportFailures`, `exportTimeouts` и AtomicBoolean `exportFailureLogged`.[^8]
- JMX‑ориентированные getters: `getDroppedSpansOverflow()`, `getDroppedSpansAfterShutdown()`, `getExportFailures()`, `getExportTimeouts()`, `getQueueCapacity()`, `getQueueSize()`, используемые PlatformExportControl MBean и документированными в dropped‑span‑reasons taxonomy.[^9][^8]

Инварианты:
- `exportTimeouts ⊆ exportFailures` — при таймауте join увеличивает оба счётчика.[^3][^8]
- `droppedSpansOverflow + exported == TOTAL` — подтверждено тестами drop‑oldest contract, обеспечивая отсутствие «тихих» потерь.[^10]
- `logExportFailureOnce` — однократный WARN на первую ошибку экспорта; последующие ошибки отражаются только в счётчиках, что важно для SRE‑поведения.[^2][^8]

### Why the class is hard to maintain

Основная сложность:
- Многопоточная логика (producer/worker/terminator) с общим lock и Condition заключена в одном классе без чётких внутренних интерфейсов, что делает любой рефакторинг опасным.[^1][^7]
- Нестандартный паттерн `awaitNanos` + явный unlock в catch + guard в finally требует очень аккуратного обращения; простое «улучшение читаемости» легко создаёт double‑unlock или deadlock для продюсеров.[^7]
- Shutdown/forceFlush координируются через общие структуры (`pendingFlushes`, latch, shutdownRequested`), и их гонки не покрыты тестами, что делает изменения поведения трудно обнаружимыми без новых тестов.
- Наблюдаемость и билдер/конфиг находятся в одном файле, хотя концептуально независимы; это создаёт шум для ревью и мешает локализовать изменения.[^2]

## 4. External Research Findings

### OpenTelemetry findings

Интерфейс `SpanProcessor` в актуальных версиях OTel SDK определяет методы `onStart`, `onEnd`, `isStartRequired`, `isEndRequired`, `forceFlush`, `shutdown`, которые должны быть потокобезопасными и не блокировать вызывающий поток сверх разумного времени.[^11][^12]

`BatchSpanProcessor` реализует очередь фиксированного размера с параметром `maxQueueSize`; при переполнении новые span'ы **отбрасываются** (drop‑new), а экспорт выполняется, когда накоплен `maxExportBatchSize` или истёк `scheduleDelay`. Именно это подтверждено в ADR‑finding, поэтому платформа не может использовать BSP напрямую для drop‑oldest.[^4][^13]

Интерфейс `SpanExporter` возвращает `CompletableResultCode` для `export`, `flush` и `shutdown`, который должен завершаться при завершении операции; при вызове `shutdown` SDK ожидает завершения всех экспортов и корректного закрытия ресурсов.[^22][^14]

`CompletableResultCode.join(long timeout, TimeUnit)` в библиотеке OTel, по обсуждению issue #2942, на таймауте может помечать результат как failure, что важно для корректного учёта `exportTimeouts` и `exportFailures`. Это подтверждает текущую модель: join с таймаутом → при недостижении success инкрементируются оба счётчика.[^15]

### Java concurrency findings

Интерфейс `Condition` и метод `awaitNanos(long nanosTimeout)` по JDK‑документации выполняют: освобождение связанного lock, блокировку до сигнала/прерывания/таймаута, затем повторное захватывание lock и возврат оценки оставшегося времени; возможны «спуриюс wakeups», поэтому вызов должен находиться в while‑цикле с проверкой условия.[^17][^18][^16]

`BlockingDeque` в JDK 21 предоставляет атомарные операции `offerLast`, `pollFirst` и т.п., но их семантика зависит от перегрузки: без таймаута `offerLast` может блокировать при полной очереди; неблокирующее drop‑oldest поведение требует явного цикла `pollFirst` + `offerLast` с внешними проверками на full, иначе `onEnd` может блокироваться.[^5]

Virtual threads и structured concurrency в Java 21 улучшают масштабируемость I/O‑bound задач, но не устраняют риски блокировок на synchronized/lock: при блокировке виртуальный поток «пинится» к carrier‑thread, что уменьшает выгоду от тысячи виртуальных потоков; рекомендация — заменять synchronized на `ReentrantLock` и минимизировать длину критических секций.[^20]

Disruptor (`RingBuffer`) предоставляет высокопроизводительный кольцевой буфер с настраиваемым WaitStrategy и поддержкой разных типов продюсеров; однако он требует нетривиальной интеграции через EventProcessor/SequenceBarrier и обычно используется для внутреннего событийного моделирования, а не как простой bounded queue; overflow‑политика и backpressure управляются конфигурируемыми стратегиями ожидания и обработчиками.[^21][^6]

### Implications for this processor

- Наличие строгого контракта `SpanProcessor.forceFlush()/shutdown()` на базе `CompletableResultCode` означает, что любые изменения forceFlush/shutdown должны сохранять семантику «возвращаемый код всегда завершается» — текущие гонки нарушают это и должны быть исправлены.[^12][^7]
- `BatchSpanProcessor` drop‑new подтверждает необходимость custom кулон drop‑oldest; использование BlockingDeque/Disruptor должно строго воспроизводить «pollFirst под lock → offerLast» семантику, иначе ADR‑инвариант будет нарушен.[^13][^8]
- Поведение `Condition.awaitNanos` усиливает риск некорректного обращения с lock в `InterruptedException` пути — любое изменение, не учитывающее возвращаемое «оставшееся время» и необходимость повторного захвата, может привести к утечке lock.[^16][^7]
- BlockingDeque как замена ручной очереди не гарантирует неблокирующее `offer` без явной конфигурации и кастомной логики; для данного класса, где `onEnd` **не должен** блокировать на I/O или очереди, прямой переход на BlockingDeque несёт высокий риск.[^5][^3]
- Virtual threads потенциально могли бы упростить управление worker/terminator, но в текущем дизайне эти потоки уже daemon и не участвуют в масштабируемой обработке запросов; выгода от замены worker на virtual thread минимальна и не оправдывает рефакторинг ядра.[^20][^7]
- Disruptor добавляет тяжёлую зависимость и сложную модель; для enterprise‑агента, где уже есть явный ReentrantLock/Condition и удовлетворительная производительность (подтверждённая JMH), такой переход выглядит избыточным.[^6][^9]

## 5. Architectural Variants

### Общая шкала и критерии

Для всех вариантов используется шкала 1–10, где 10 — лучшее значение по критерию; веса критериев заданы в задаче (сумма 100). Оценки базируются на анализе responsibilities C1–C8, текущих рисков R1–R6 и ограничений из `06-refactoring-constraints.md`.[^3][^1]

#### Критерии (веса)

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

Ниже рассмотрены V1–V8 с детализацией.

***

### V1 — Status quo + targeted fixes only

#### Description

Вариант предполагает минимальные изменения внутри существующего монолитного класса: добавить bounded‑обработку для `exporter.shutdown()` (таймаут/обрыв), усилить координацию forceFlush+shutdown, ввести недостающие тесты M1–M9 и оставить структуру очереди, worker‑loop и билдер/наблюдаемость без декомпозиции.[^4]

#### New classes/components

Новых классов не добавляется; возможно появление небольшой приватной утилитарной функции для shutdown‑таймаута, но остаётся в том же файле.

#### Field/method moves

Не предполагается перемещение полей: `queue`, `queueLock`, `shutdownRequested`, counters, `pendingFlushes`, worker/terminator остаются на месте; меняется только логика внутри `shutdown()` (добавление таймаута вокруг `exporter.shutdown()` и обработка незавершающегося `CompletableResultCode`) и, возможно, ветка `forceFlush()`/`workerLoop` для гарантированного завершения промисов.[^8][^7]

#### Preserved behavior

- Drop‑oldest semantics: `pollFirst()` перед `offerLast()` под lock остаётся неизменной.[^8]
- `onEnd` по‑прежнему неблокирующий (lock только вокруг O(1) операций на ArrayDeque и signalAll).[^3][^8]
- `exporter.export()` вызывается вне lock.[^3][^7]
- Unsampled spans игнорируются; используются `SpanData` snapshots.[^8]
- Идемпотентный shutdown и forceFlush после shutdown → ofSuccess.[^3][^8]
- Счётчики и JMX‑getters сохраняют семантику и сигнатуры.[^3][^8]

#### Behavior that could accidentally change

- Добавление таймаута вокруг `exporter.shutdown()` может привести к изменению момента инкремента `droppedSpansAfterShutdown` (если таймаут интерпретируется как failure и проводится доп.дренаж).[^7][^8]
- Исправление гонки forceFlush+shutdown (например, принудительное fail всех промисов после shutdown) изменит текущую, хотя и дефектную, семантику «обещание может не завершиться»; это нужно явно задокументировать в ADR.[^7]

#### Concurrency risks

- Основные риски R2 (double‑unlock hazard), R4 (worker death) остаются; targeted‑fixes их не устраняют.[^2][^7]
- Добавление таймаута shutdown может добавить прерывания/сигналы, ещё сильнее задев тот же нестандартный блок `InterruptedException` в workerLoop.[^7]

#### Shutdown risks

- Исправление R1 уменьшает риск вечного блока SDK, но если реализовать неправильно (например, оставить `exporter.shutdown()` без таймаута), риск сохранится.[^2]
- Без структурных изменений shutdown остаётся двухнитевым, с теми же гонками на `shutdownRequested` и queueLock.

#### forceFlush risks

- Вариант целенаправленно должен устранить R3: добавить явную обработку случая, когда `shutdownRequested` стал true до обработки pendingFlushes, например, fail всех висящих промисов при завершении worker или в shutdown‑terminator.[^7]
- При неправильной реализации возможно нарушение контракта «forceFlush после shutdown возвращает ofSuccess», если логика будет слишком агрессивной.

#### Observability/JMX impact

Счётчики и getters не меняются; могут добавиться новые WARN/ERROR‑логи вокруг shutdown‑таймаута и forceFlush‑fail, но это считается улучшением наблюдаемости.[^2][^3]

#### Performance impact

Исправления не меняют структуру очереди/worker; добавление shutdown‑таймаута и extra‑проверок в forceFlush не влияет на steady‑state `onEnd` path и QueueOfferBenchmark.[^9][^3]

#### Testability impact

Добавление тестов M1–M9 и эксплицитных веток в код делает систему лучше покрытой и даёт основу для будущих рефакторингов. При этом класс остаётся большим, но многие крайние сценарии будут зафиксированы.[^10]

#### Dependency impact

Нет новых внешних зависимостей.

#### Migration steps

- Phase 0: добавить M1–M9 и запустить их на текущей реализации, ожидая, что некоторые упадут (особенно M1, M2, M4, M7).[^10]
- Внести локальные правки в `shutdown()` и `forceFlush()/workerLoop` для прохождения новых тестов.
- Зафиксировать обновлённый JMH‑бенчмарк и сравнить с baseline (ожидается равенство).[^9]

#### Required characterization tests

- M1–M4, M7, M8, M9 из test‑gaps как обязательные до/во время реализации.[^10]

#### Rollback path

Любые targeted‑фиксы легко откатываются через revert PR; на уровне продакшена rollback через снятие env‑var `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` и возврат к BSP остаётся доступным.[^3]

#### Verdict

V1 — наиболее безопасный вариант для **очень короткого горизонта**, если цель — минимально снизить риск R1/R3, не трогая архитектуру. Однако он не решает корневую проблему поддерживаемости и сложной конкуррентной структуры.

***

### V2 — Extract TimedExporter only

#### Description

Выделение тайм‑аутного экспорта в отдельный package‑private компонент C3: `TimedExporter`, который инкапсулирует `exportBatch()`, `exportTimeoutNanos`, `exportFailures`, `exportTimeouts`, `exportFailureLogged` и всю логику join/timeout/failure классификации.[^1]

#### New classes/components

- `TimedExporter` (package‑private, в том же пакете): поля `SpanExporter exporter`, `long exportTimeoutNanos`, счётчики, метод `boolean exportWithTimeout(List<SpanData> batch)`.

#### Field/method moves

- Из основного класса в `TimedExporter` переезжают: `exporter`, `exportTimeoutNanos`, `exportBatch()`, counters `exportFailures`, `exportTimeouts`, `exportFailureLogged`.[^1]
- Основной класс вместо `exportBatch` вызывает `timedExporter.exportWithTimeout(batch)` и использует его результат для завершения forceFlush‑промисов и решения, повторять ли дренаж при shutdown.

#### Preserved behavior

- Контракт `exportTimeouts ⊆ exportFailures` сохраняется внутри `TimedExporter`.[^3]
- Логика `logExportFailureOnce` (однократный WARN) остаётся неизменной.[^1][^8]
- Вызов `exporter.export()` по‑прежнему происходит вне `queueLock`, так как `TimedExporter` вызывается из `tryExport` после unlock.[^7]

#### Behavior that could accidentally change

- Если `TimedExporter` реализация ошибочно изменит порядок инкремента счётчиков, может нарушиться наблюдаемостный инвариант.[^3]
- Неправильная интеграция может изменить семантику возврата `false`/`true` из экспортного метода, влияя на forceFlush‑результаты.

#### Concurrency risks

- `TimedExporter` сам по себе не использует lock; единственный риск — многопоточный доступ к счетчикам и логгеру, который как и раньше основан на AtomicLong/AtomicBoolean и потокобезопасен.[^1]

#### Shutdown risks

- Shutdown‑логика остаётся в основном классе; R1 не решён.

#### forceFlush risks

- Семантика forceFlush остаётся прежней; R3 не решён напрямую.

#### Observability/JMX impact

Сигнатуры getters не меняются; счётчики просто физически переезжают в другую сущность, но остаются читаться через методы основного класса.[^1][^3]

#### Performance impact

- В steady‑state `onEnd` path нет изменений; добавляется один уровень вызова (`timedExporter.exportWithTimeout`), но это микроскопический overhead.[^9]

#### Testability impact

- `TimedExporter` становится отдельно тестируемым: можно написать unit‑тесты на таймаут, ошибки экспорта и throttle без поднятия очереди/worker.[^10][^1]
- Облегчает дифференцирование проблем: если тесты экспортного поведения падают, проблема локализуется в C3.

#### Dependency impact

Новых внешних зависимостей нет.

#### Migration steps

- Phase 0: как для V1.
- Внести V2 после Phase 0 или параллельно, убедившись, что все существующие тесты export‑поведения проходят.

#### Required characterization tests

- Уже существующие tests `exportTimeoutIncrementsCounter`, `exporterExceptionIsolatedAndCounted` будут использовать новый компонент; желательно добавить unit‑тесты на C3 напрямую.[^10]

#### Rollback path

- Простая замена на inline‑реализацию `exportBatch` и удаление класса `TimedExporter`, без касания публичного API.

#### Verdict

V2 — низкорисковая структурная чистка, повышающая локализуемость ошибок и тестируемость, но не решающая ключевые concurrency/shutdown проблемы. В сочетании с V3/V5 даёт хороший фундамент для дальнейшей декомпозиции.

***

### V3 — Extract builder/config into immutable config

#### Description

Извлечение Builder/валидации/BSP‑чтения в отдельную сущность C6: `ProcessorConfig` (immutable record) и, при необходимости, утилиту `ProcessorConfigBuilder`, оставляя публичный `Builder` как фасад.[^1]

#### New classes/components

- `ProcessorConfig` (record): поля `maxQueueSize`, `maxExportBatchSize`, `scheduleDelay`, `exportTimeout`, `shutdownTimeout` и т.п.[^1]
- Внутренний helper для чтения BSP‑ключей и применения валидирующих fallback‑правил.

#### Field/method moves

- `Builder`, `applyValidationWithSafeFallback`, `readBspConfigFrom` и связанный код переезжают в отдельный файл; основной класс получает уже готовый `ProcessorConfig` в конструкторе.[^3][^1]

#### Preserved behavior

- Публичный API `PlatformDropOldestExportSpanProcessor.builder(SpanExporter)` и методы Builder остаются неизменными (фасад, делегирующий внутрь).[^3]
- Fallback‑поведение (WARN + defaults, кроме null exporter) сохраняется.[^8][^3]
- Чтение BSP‑ключей `otel.bsp.*` остаётся совместимым.[^9][^8]

#### Behavior that could accidentally change

- Ошибка при переносе может изменить default‑значения (маппинг BSP‑которые → Duration/ints).[^3]
- Могут измениться тексты WARN‑логов или порядок их эмиссии — это должен учитывать SRE/ADR.

#### Concurrency risks

- Конфиг используется только в конструкторе, без многопоточной мутации; риск минимален.

#### Shutdown risks

- Shutdown‑поведение не меняется.

#### forceFlush risks

- Не затрагивается.

#### Observability/JMX impact

- JMX gettter'ы не меняются; перемещается только код получения настроек.

#### Performance impact

- Построение конфигурации — одноразовая операция при инициализации; runtime‑эффект отсутствует.[^3]

#### Testability impact

- Builder/Config‑тесты становятся более точными и отделяются от логики очереди/воркера.[^10]

#### Dependency impact

- Нет новых зависимостей.

#### Migration steps

- Выполнить после Phase 0, но до более рискованных вариантов (V4/V6), чтобы разгрузить основной класс.

#### Required characterization tests

- Уже существующие builder‑validation тесты должны покрыть новую реализацию; дополнительно добавить кейс `maxExportBatchSize(0)`.[^10]

#### Rollback path

- Возврат Builder и валидации «назад» в основной класс.

#### Verdict

V3 — почти нулевой риск и высокая польза с точки зрения читабельности и тестируемости; его следует включить в Phase 1 как один из первых шагов.

***

### V4 — Extract bounded drop-oldest queue

#### Description

Выделение C1 `BoundedDropOldestQueue`, который владеет ArrayDeque, `queueLock`, `queueNotEmpty`, eviction‑логикой, размером, capacity и частично счётчиками overflow.[^1]

#### New classes/components

- `BoundedDropOldestQueue`: методы `enqueue(SpanData, LifecycleGuard)`, `drainBatch(int maxExportBatchSize)`, `size()`, `capacity()`, `signalFlush()` и т.п.[^1]

#### Field/method moves

- В новый класс переходят: `queue`, `queueLock`, `queueNotEmpty`, `maxQueueSize`, `enqueueWithDropOldest`, `drainBatchLocked`, `queueSizeSafe`, `droppedSpansOverflow`.[^1]
- Основной класс теперь работает через интерфейс очереди; worker/forceFlush/shutdown используют её API вместо прямого доступа к lock/ArrayDeque.

#### Preserved behavior

- Drop‑oldest `pollFirst()` + `offerLast()` сохраняется в реализации C1.[^8][^1]
- `droppedSpansOverflow + exported == TOTAL` можно сохранить, если тесты остаются неизменными.[^10]

#### Behavior that could accidentally change

- Естественная связка shutdownRequested + enqueueUnderLock может быть разорвана: если C1 не знает o shutdown, double‑check состояния должен быть параметром или callback; ошибки здесь изменят semantics `droppedSpansAfterShutdown`.[^1][^7]

#### Concurrency risks

- Наибольший риск — перенос нестандартной InterruptedException‑обработки: сейчас worker непосредственно управляет lock; если этот код останется в worker, а lock переедет в C1, возникает cross‑class coupling.[^7]
- Неправильная абстракция может позволить `exporter.export()` случайно вызвать под lock, если интерфейс C1 объединит drain+export.[^3][^1]

#### Shutdown risks

- Shutdown (terminator) зависит от возможности под lock считать `queue.size()` и очистить её; C1 должен экспонировать безопасный API для этого.[^8][^7]

#### forceFlush risks

- `pendingFlushes` зависят от того же lock; либо они переезжают в C1, либо C4 должна «заимствовать» lock C1 — сложная модель.[^1]

#### Observability/JMX impact

- Getters `getQueueSize()`/`getQueueCapacity()` будут делегировать в C1; при аккуратном переносе семантика сохранится.[^8][^3]

#### Performance impact

- Если C1 реализован тонко, O(1) операции ArrayDeque сохранятся; возможен небольшой overhead на виртуальные вызовы.[^9]
- Любая ошибка или добавление лишних проверок внутри lock может увеличить время удержания lock и деградировать QueueOfferBenchmark.

#### Testability impact

- Queue‑контракт можно тестировать отдельно (identity proof, overflow counters), но высокорискованный worker+interrupt паттерн по‑прежнему требует интеграционных тестов.[^10][^7]

#### Dependency impact

- Нет внешних зависимостей.

#### Migration steps

- Выполнить только после Phase 0 и минимальных извлечений C3/C6; возможно в составе V6.

#### Required characterization tests

- Все SP‑05 и overflow‑tests, плюс новые тесты M1–M3, чтобы убедиться в сохранении семантики queue‑lock + pendingFlushes.[^10]

#### Rollback path

- Возврат очереди в основной класс; при этом потребуется аккуратный revert интерфейсов.

#### Verdict

V4 — полезное по целям архитектуры, но высокорисковое изменение в контексте текущего нестандартного lock‑паттерна; его лучше рассматривать как часть V6 после стабилизации тестов, а не как ранний шаг.

***

### V5 — Low-risk decomposition bundle

#### Description

Комбо извлечений низкого риска: C6 (ProcessorConfig/Builder), C3 (TimedExporter), C7 (observability snapshot), опционально C8 (LifecycleStateGuard), **без** вынесения очереди/worker/lock.[^4][^1]

#### New classes/components

- `ProcessorConfig`/Builder helper (как в V3).
- `TimedExporter` (как в V2).
- `ObservabilitySnapshot` или просто internal holder для counters/JMX‑getters.[^1]
- `LifecycleStateGuard` для централизованного `shutdownRequested` (если включать C8).[^1]

#### Field/method moves

- Config/build‑логика, экспортные счётчики и часть getter‑логики переезжают в отдельные классы.[^1]
- Основной класс становится фасадом над компонентами, но сохраняет worker/queue/forceFlush/shutdown внутри.

#### Preserved behavior

- Все инварианты из `01-current-behavior.md` и `06-refactoring-constraints.md` могут быть без труда сохранены, так как concurrency‑критические части остаются нетронутыми.[^8][^3]

#### Behavior that could accidentally change

- Потенциально — наблюдаемость, если getters будут читать счётчики неконсистентно или изменится способ выдачи `queueSize` (например, без lock).[^3][^1]

#### Concurrency risks

- Риск минимален: новые компоненты не владеют lock, а просто читают/пишут AtomicLong/AtomicBoolean или используются в конструкторе.[^7][^1]

#### Shutdown risks

- Не меняются.

#### forceFlush risks

- Не меняются.

#### Observability/JMX impact

- Структурное улучшение: JMX‑ориентированная логика концентрируется в отдельном слое, готовом для возможной миграции к Micrometer/Prometheus; семантика counters+getters сохраняется.[^9][^1]

#### Performance impact

- Runtime‑путь `onEnd`/worker остаётся прежним; добавляются лишь косвенные делегации.[^9]

#### Testability impact

- Конфиг/экспорт/наблюдаемость становятся более тестируемыми отдельно (unit‑тесты на C3/C6/C7).[^10]
- Это создаёт основу для последующего переписывания worker/queue (V6) с меньшим шумом.

#### Dependency impact

- Нет новых зависимостей.

#### Migration steps

- Phase 0: тесты+JMH.
- Phase 1: внедрить V5 (C6, C3, C7, опционально C8), убедиться, что все тесты проходят и JMH не деградирует.[^3]

#### Required characterization tests

- Все tests из текущего набора + специфические unit‑tests для новых компонентов; никаких новых concurrent‑edge‑тестов не требуется, так как concurrency не меняется.[^10]

#### Rollback path

- Простое возвращение логики в основной класс.

#### Verdict

V5 — лучший кандидат на **первый серьёзный рефакторинговый PR**: даёт архитектурные выигрыши, не трогая самое рискованное место (queue/worker). Его стоит принять как near‑term variant после выполнения Phase 0.

***

### V6 — Full internal decomposition

#### Description

Полная декомпозиция внутренностей в компоненты C1–C8, при сохранении `PlatformDropOldestExportSpanProcessor` как публичного фасада: очередь, worker, timedExporter, forceFlush‑координатор, shutdown‑координатор, config, observability, lifecycle‑guard — отдельные классы/объекты.[^4][^1]

#### New classes/components

- C1–C8 в полном объёме (см. responsibility decomposition).[^1]

#### Field/method moves

- Worker/queue/lock/pendingFlushes/shutdownRequested/shutdownResult/workerTerminated/counters переезжают по своим компонентам; фасад связывает их и реализует SpanProcessor API.[^1]

#### Preserved behavior

- При аккуратном переписывании можно сохранить все публичные инварианты: drop‑oldest, неблокирующий onEnd, idempotent shutdown/forceFlush after shutdown, счётчики и JMX‑getters.[^8][^3]

#### Behavior that could accidentally change

- Любая ошибка в контракте между C1 (queue), C2 (worker), C4 (flush) и C5 (shutdown) может привести к изменению семантики: пропущенные промисы, гонки shutdown vs enqueue, изменение момента дренажа очереди и т.д.[^7][^1]

#### Concurrency risks

- Высокие: необходимо перепроектировать нестандартный `awaitNanos`/unlock паттерн, возможно, заменив на более стандартную схему, но при этом сохранить производительность и неблокирующий onEnd.[^7]
- Ошибки в координации C4/C5 могут усилить или изменить существующие гонки.

#### Shutdown risks

- Shutdown‑координатор (C5) должен корректно управлять worker, очередью и exporter; неправильный дизайн может привести к новым случаям подвисания или преждевременного завершения shutdownResult.[^3][^1]

#### forceFlush risks

- Путь pendingFlushes должен быть чётко определён; при ошибках возможны потерянные или «лишние» completion'ы.

#### Observability/JMX impact

- Счётчики и getters будут читаться через ObservabilitySnapshot; при соблюдении контракта никаких внешних изменений, но внутренний снэпшот может в будущем позволить атомарное чтение сразу всех значений.[^1]

#### Performance impact

- Потенциально нейтральная, но требует тщательного бенчмаркинга: введение дополнительных объектов/делегаций может увеличить latency `onEnd`; в то же время упрощение lock‑паттерна может даже улучшить ситуацию.[^9]

#### Testability impact

- Максимальный выигрыш: каждый компонент получает свои тесты, Concurrency/Shutdown/Flush‑пути становятся более адресуемыми.[^10][^1]

#### Dependency impact

- Нет внешних зависимостей.

#### Migration steps

- Реализовать после Phase 0–2: сначала V5, затем постепенное перенесение C1/C2/C4/C5 с активным использованием новых characterization tests и новых unit‑тестов.

#### Required characterization tests

- Все M1–M9, плюс дополнительные тесты на формальные новые интерфейсы между C1–C5.[^10]

#### Rollback path

- Более сложен: откат полной декомпозиции потребует глобального revert PR; на уровне продакшена остаётся rollback через env‑var и возврат к stock BSP.[^3]

#### Verdict

V6 — желаемая долгосрочная архитектура, но слишком рискованная как первый шаг; она должна стать целью после закрепления малых извлечений и усиления тестовой базы.

***

### V7 — Replace manual queue/condition with Java concurrency primitives

#### Description

Замена самописной ArrayDeque+ReentrantLock+Condition на стандартные коллекции и исполнители, например: `LinkedBlockingDeque`/`ArrayBlockingQueue`, `ExecutorService`/worker pool, возможно виртуальные потоки.[^20][^5][^4]

#### New classes/components

- Использование `BlockingDeque<SpanData>` вместо ручной очереди.
- Worker, реализованный как задача в ExecutorService (в том числе на базе virtual threads).[^20][^5]

#### Field/method moves

- `queue`, `queueLock`, `queueNotEmpty` исчезают; вместо них — блокирующая очередь/исполнитель.

#### Preserved behavior

- Drop‑oldest можно реализовать через цикл `if (deque.size()==capacity) deque.pollFirst(); deque.offerLast(span)`; необходимо строго гарантировать неблокирующий характер операций (используя неблокирующее `offerLast`/ручные проверки).[^5]

#### Behavior that could accidentally change

- Стандартные BlockingDeque методы по умолчанию могут блокировать при полной очереди; если использовать `put` или `offer` с таймаутом, `onEnd` начнёт блокироваться, что нарушит жёсткий контракт.[^5][^3]
- Планировщик (ExecutorService, особенно на виртуальных потоках) может изменить момент экспорта и профили задержки scheduleDelay/forceFlush.

#### Concurrency risks

- Хотя стандартные примитивы надёжны, смещение от hand‑rolled к BlockingDeque может породить новые блокировки и ждать; это сложно сочетать с требованием non‑blocking `onEnd`.

#### Shutdown risks

- Завершение ExecutorService и BlockingDeque нужно реализовать аккуратно; возможны зависания, если не завершить все задачи и не закрыть очереди.[^23]

#### forceFlush risks

- Придётся переписать логику pendingFlushes на фоне новой очереди/воркера; риск высок.

#### Observability/JMX impact

- Счётчики могут остаться прежними, но необходимо убедиться, что очередь даёт корректный size/capacity.

#### Performance impact

- BlockingDeque и ExecutorService могут иметь иной overhead, чем лёгкий lock+ArrayDeque; для latency‑чувствительного агента это может привести к регрессии JMH.[^5][^9]

#### Testability impact

- Стандартные примитивы проще в reasoning, но новые concurrency‑пути требуют свежих тестов; существующие тесты должны быть адаптированы.

#### Dependency impact

- Только стандартные JDK‑классы, без внешних зависимостей.

#### Migration steps

- Возможна только после очень тщательной проработки, и фактически это уже новая архитектура; на данном этапе слишком рискованно.

#### Required characterization tests

- Все M1–M9 + новые тесты на отсутствие блокировок `onEnd` при полной очереди.

#### Rollback path

- Полный revert на предыдущую реализацию очереди/worker.

#### Verdict

V7 — теоретически привлекательный, но практически рискованный: стандартные concurrency‑примитивы не дают drop‑oldest+non‑blocking «из коробки» и требуют сложной настройки; его лучше явно отклонить для текущей платформы.

***

### V8 — Ring buffer / Disruptor-style architecture

#### Description

Замена очереди/worker на Disruptor‑подобную ring‑buffer архитектуру (LMAX Disruptor или кастомный bounded ring buffer), где producer публикует события, а consumer(s) их обрабатывают.[^21][^6][^4]

#### New classes/components

- External dependency на `com.lmax.disruptor` или кастомный ring buffer.
- EventProcessor/Handler, который выполняет экспорт и координацию forceFlush/shutdown.

#### Field/method moves

- `queue`, `queueLock`, `queueNotEmpty`, workerThread — заменяются на ring buffer, WaitStrategy и consumer‑логику.

#### Preserved behavior

- Bounded очередность (ring buffer) сохраняется; можно реализовать drop‑oldest, если настроить overflow‑policy соответствующим образом.

#### Behavior that could accidentally change

- Disruptor имеет другую модель задержек и backpressure; семантика scheduleDelay, batch size и flush может существенно измениться.[^6]
- Обработка shutdown/forceFlush должна интегрироваться в event‑логику, что усложняет reasoning и increases risk.

#### Concurrency risks

- Disruptor требует правильной конфигурации producerType, WaitStrategy и барьеров; ошибки здесь приводят к subtle concurrency bugs.[^21]

#### Shutdown risks

- Shutdown event обработчиков может не просто; потребуется собственный протокол остановки, сопряжённый с exporter.shutdown.

#### forceFlush risks

- ForceFlush должна быть реализована как событие с подтверждением; возможно, придётся менять контракт.[^4]

#### Observability/JMX impact

- Счётчики можно оставить или дополнить; но семантика их инкремента может измениться.

#### Performance impact

- Disruptor рассчитан на очень высокую пропускную способность; однако текущий QueueOfferBenchmark уже даёт удовлетворительную производительность, и выгода может быть минимальной; риск переинженеринга велик.[^6][^9]

#### Testability impact

- Тесты станут сложнее: потребуется unit‑тестировать Disruptor‑потоки и последовательности; это поднимает порог входа для команды.

#### Dependency impact

- Добавляется внешняя зависимость (LMAX Disruptor), что усложняет инфраструктуру агента.[^6]

#### Migration steps

- Потребуются серьёзные ADR‑изменения и возможно новый проект; для текущей задачи это вне scope.

#### Required characterization tests

- Практически новый набор тестов; существующие M1–M9 придётся адаптировать к другой модели.

#### Rollback path

- Вернуться к прежней реализации, удалив зависимость.

#### Verdict

V8 — явный over‑engineering для данного случая: текущая производительность приемлема, а сложность Disruptor/кольцевого буфера не оправдана; вариант следует отклонить.

## 6. Scoring Matrix

### Raw scores per variant

(1–10, высокий балл = лучше; оценки основаны на аналитике выше.)

| Variant | Behavior preservation | Concurrency safety | Shutdown correctness | ForceFlush correctness | Maintainability | Testability | Performance neutrality | Observability compatibility | Incremental deliverability | Dependency footprint |
|--------|------------------------|---------------------|----------------------|------------------------|-----------------|------------|------------------------|-----------------------------|---------------------------|---------------------|
| V1     | 9                      | 5                   | 7                    | 7                      | 4               | 7          | 9                      | 9                           | 9                         | 10                  |
| V2     | 9                      | 8                   | 7                    | 7                      | 6               | 8          | 9                      | 9                           | 8                         | 10                  |
| V3     | 10                     | 9                   | 8                    | 8                      | 8               | 9          | 10                     | 10                          | 9                         | 10                  |
| V4     | 8                      | 5                   | 6                    | 6                      | 7               | 7          | 8                      | 8                           | 5                         | 10                  |
| V5     | 10                     | 9                   | 8                    | 8                      | 9               | 9          | 10                     | 10                          | 9                         | 10                  |
| V6     | 9                      | 7                   | 8                    | 8                      | 10              | 10         | 9                      | 9                           | 5                         | 10                  |
| V7     | 6                      | 6                   | 6                    | 6                      | 7               | 7          | 7                      | 8                           | 4                         | 9                   |
| V8     | 5                      | 5                   | 5                    | 5                      | 6               | 6          | 8                      | 7                           | 2                         | 6                   |

### Weighted totals

Умножая на веса:

- V1: 9*18 + 5*16 + 7*12 + 7*10 + 4*12 + 7*10 + 9*8 + 9*6 + 9*5 + 10*3 = 162 + 80 + 84 + 70 + 48 + 70 + 72 + 54 + 45 + 30 = **715**
- V2: 9*18 + 8*16 + 7*12 + 7*10 + 6*12 + 8*10 + 9*8 + 9*6 + 8*5 + 10*3 = 162 + 128 + 84 + 70 + 72 + 80 + 72 + 54 + 40 + 30 = **792**
- V3: 10*18 + 9*16 + 8*12 + 8*10 + 8*12 + 9*10 + 10*8 + 10*6 + 9*5 + 10*3 = 180 + 144 + 96 + 80 + 96 + 90 + 80 + 60 + 45 + 30 = **901**
- V4: 8*18 + 5*16 + 6*12 + 6*10 + 7*12 + 7*10 + 8*8 + 8*6 + 5*5 + 10*3 = 144 + 80 + 72 + 60 + 84 + 70 + 64 + 48 + 25 + 30 = **677**
- V5: 10*18 + 9*16 + 8*12 + 8*10 + 9*12 + 9*10 + 10*8 + 10*6 + 9*5 + 10*3 = 180 + 144 + 96 + 80 + 108 + 90 + 80 + 60 + 45 + 30 = **913**
- V6: 9*18 + 7*16 + 8*12 + 8*10 + 10*12 + 10*10 + 9*8 + 9*6 + 5*5 + 10*3 = 162 + 112 + 96 + 80 + 120 + 100 + 72 + 54 + 25 + 30 = **851**
- V7: 6*18 + 6*16 + 6*12 + 6*10 + 7*12 + 7*10 + 7*8 + 8*6 + 4*5 + 9*3 = 108 + 96 + 72 + 60 + 84 + 70 + 56 + 48 + 20 + 27 = **641**
- V8: 5*18 + 5*16 + 5*12 + 5*10 + 6*12 + 6*10 + 8*8 + 7*6 + 2*5 + 6*3 = 90 + 80 + 60 + 50 + 72 + 60 + 64 + 42 + 10 + 18 = **546**

### Final ranking (default weights)

1. V5 — 913
2. V3 — 901
3. V6 — 851
4. V2 — 792
5. V1 — 715
6. V4 — 677
7. V7 — 641
8. V8 — 546

### Confidence level, recommendation, risk, tests, complexity, rollback

| Variant | Weighted total | Rank | Confidence | Recommendation | Biggest risk | Required tests | Complexity (est.) | Rollback strategy |
|---------|----------------|------|-----------|----------------|-------------|----------------|-------------------|-------------------|
| V1 | 715 | 5 | High | CAUTION | Сохранение сложного lock‑паттерна и R4/R2 | M1–M4, M7–M9 | Низкая | Revert фиксов, env‑rollback |
| V2 | 792 | 4 | High | GO (как часть V5) | Ошибка в переносе счётчиков/timeout | Existing export tests + unit на TimedExporter | Низкая | Revert, без API изменений |
| V3 | 901 | 2 | High | GO (Phase 1) | Ошибка в BSP‑defaults | Builder tests + SharedDefaultsAlignment | Низкая–средняя | Revert Builder/Config |
| V4 | 677 | 6 | Medium | CAUTION (позже, вместе с V6) | Неправильный контракт shutdown/enqueue | SP‑05 + M1–M3 | Высокая | Трудоёмкий revert, но возможен |
| V5 | 913 | 1 | High | GO (near-term) | Мелкие наблюдаемостные регрессии | Все существующие + unit‑tests на C3/C6/C7 | Средняя | Revert структурных извлечений |
| V6 | 851 | 3 | Medium | CAUTION (long-term target) | Новые concurrency‑гоночные баги | M1–M9 + новые интерфейсные тесты | Высокая | Большой revert; env‑rollback для продакшена |
| V7 | 641 | 7 | Low | NO GO | Блокировки `onEnd` и изменение semantics drop‑oldest | M1–M9 + non‑blocking tests | Высокая | Revert на ручную очередь |
| V8 | 546 | 8 | Low | NO GO | Over‑engineering, сложность Disruptor | Новый набор тестов, фактически другой продукт | Очень высокая | Удаление зависимости и revert |

## 7. Sensitivity Analysis

### Увеличение веса concurrency safety

Если увеличить вес Concurrency safety, например, с 16 до 25 (за счёт уменьшения других критериев), варианты, дающие улучшение безопасности без структурного вмешательства, поднимаются: V5 и V3 остаются лидерами, V6 немного теряет из‑за высокой сложности, V1/V2 слегка выигрывают, а V4/V7/V8 не выходят в топ.[^3][^1]

Рекомендуемый near‑term путь не меняется: V5 остаётся первым, так как он не ухудшает concurrency‑риски и создаёт базу для V6.

### Увеличение веса maintainability

При увеличении веса Maintainability (например, с 12 до 20) V6 получает больше очков и может приблизиться к V5, а V3 остаётся высоким, тогда как V1/V2/V4/V7/V8 сильно отстают. Однако из‑за высокого риска V6 как первого шага рекомендация остаётся: сначала V5/V3, затем V6.[^1]

### Приоритизация implementation simplicity

Если фокус сместить на Implementation simplicity (т.е. повысить веса Incremental deliverability и Dependency footprint, понизив остальные), V3 и V1/V2 могут временно опередить V5/V6 с точки зрения лёгкости внедрения. Но это противоречит цели снижения долгосрочных рисков; в контексте production‑критичности «простота» не должна доминировать над safety.

### Does final recommendation change?

Во всех рассмотренных сценариях чувствительности near‑term рекомендация остаётся V5 (с включением V3/V2 внутри Phase 1), а long‑term target остаётся V6; V7/V8 consistently остаются NO GO.

## 8. Recommended Architecture

### Best conservative path

Лучший консервативный путь:
- Phase 0: добавить характеризационные тесты и JMH‑baseline без рефакторинга.[^2][^10]
- Phase 1: реализовать V5 (C6, C3, C7, опционально C8), не трогая очередь/worker.[^1]
- Phase 2: targeted hardening (V1‑style) — фиксы shutdown/forceFlush, основываясь на новых тестах.[^2][^7]

### Best long-term architecture

Долгосрочная цель — V6: структурированная архитектура с компонентами C1–C8 и публичным фасадом `PlatformDropOldestExportSpanProcessor`, который реализует SpanProcessor API и JMX‑контракт, делегируя к внутренним объектам.[^1]

### Recommended extraction order

С учётом responsibility decomposition:

1. C6 (ProcessorConfig/Builder) — минимальный риск, сразу улучшает читаемость.[^1]
2. C3 (TimedExporter) — низкий риск, улучшает тестируемость export‑пути.[^1]
3. C7 (Observability/Getter holder) — structural only.[^1]
4. C8 (LifecycleStateGuard) — тривиально; уменьшает дублирование shutdownRequested‑чтения.[^1]
5. C1+C2 (Queue+Worker) — совместное перепроектирование, только после стабилизации тестов M1–M9.[^7][^1]
6. C4+C5 (ForceFlush+Shutdown coordinator) — завершающая стадия, опирающаяся на стабильные C1/C2.

### Package/class layout

- `space.br1440.platform.tracing.otel.extension.processor.PlatformDropOldestExportSpanProcessor` — фасад SpanProcessor.
- `space.br1440.platform.tracing.otel.extension.processor.internal.ProcessorConfig`.
- `...internal.TimedExporter`.
- `...internal.ObservabilitySnapshot`/`ObservabilityState`.
- `...internal.LifecycleStateGuard`.
- Позже: `...internal.BoundedDropOldestQueue`, `ExportWorker`, `ForceFlushCoordinator`, `ShutdownCoordinator`.[^9][^1]

### Dependency direction

- Внешние зависимости: `SpanExporter`, `ConfigProperties`, JMX‑MBean (PlatformExportControl), остаются только на фасаде и C3/C6/C7.[^9]
- Внутри: Config → Queue/Worker/TimedExporter/Shutdown; Queue/Worker не зависят от JMX/Builder; Observability читает state read‑only, не влияет на поведение.[^1]

## 9. Phased Migration Plan

### Phase 0: characterization tests and benchmark baseline

- Goal: зафиксировать текущую семантику, обнаружить скрытые гонки и получить производительный baseline.
- Changes: добавить тесты M1–M9, настроить захват логов для проверки `logExportFailureOnce`, добавить gate на QueueOfferBenchmark.[^9][^10]
- Tests: все существующие + M1–M9; JMH run для `dropOldestOfferSteady` и `dropOldestOfferSaturatedEviction`.[^9][^10]
- Risks: небольшие изменения тестов/benchmark; нет runtime‑рисков.
- Rollback: нет необходимости.
- Merge criteria: все тесты зелёные; baseline сохранён.

### Phase 1: low-risk extractions

- Goal: реализовать V5 (C6, C3, C7, C8).
- Changes: вынести Builder/Config, TimedExporter, Observability holder, Lifecycle guard; обновить тесты, не меняя поведение.[^1]
- Tests: builder‑validation, export‑lifecycle, JMX‑getters; новые unit‑tests для C3/C6/C7.[^10]
- Risks: небольшие ошибки конфигов или счётчиков.
- Rollback: revert PR; env‑rollback на уровне продакшена не требуется.
- Merge criteria: все тесты и JMH без регрессий; JMX‑контракт сохранён.[^3]

### Phase 2: targeted production hardening

- Goal: устранить R1, R3, R4 и другие выявленные риски без структурной декомпозиции queue/worker.
- Changes: bounded shutdown таймаут для exporter.shutdown, гарантированное завершение forceFlush‑промисов при shutdown, улучшенная обработка RuntimeException в workerLoop (например, сигнал через отдельный счётчик/лог и controlled shutdown).[^2][^7]
- Tests: M1–M4, M7–M9; новые тесты для death‑path воркера и shutdown‑hang.[^10]
- Risks: изменения forceFlush/shutdown‑семантики.
- Rollback: revert PR.
- Merge criteria: все tests pass; SDK shutdown не блокируется при hang exporter; JMH не деградирует.[^14][^12]

### Phase 3: queue/worker decomposition if still justified

- Goal: реализовать части V4/V6 (C1+C2, возможно C4+C5) после того, как база стабилизирована.
- Changes: рефакторинг очереди/worker с сохранением инвариантов и явным контрактом на lock‑владение и interrupt‑поведение.[^7][^1]
- Tests: SP‑05, M1–M9, новые интеграционные тесты на C1/C2/C4/C5.
- Risks: высокие concurrency‑риски; необходимо поэтапное внедрение и длительное тестирование.
- Rollback: revert PR; при серьёзных проблемах — отключение DROP_OLDEST.[^3]
- Merge criteria: все tests зелёные, JMH не падает, SRE‑диагностика OK.

### Phase 4: cleanup and ADR update

- Goal: зафиксировать новую архитектуру, обновить ADR и документацию.[^9]
- Changes: обновление ADR‑файлов (drop‑oldest processor v2), документации по JMX, тестовым описаниям; возможное удаление legacy‑путей.
- Tests: sanity pass всех тестов; никакого функционального изменения.
- Risks: минимальные.
- Rollback: не требуется.
- Merge criteria: документация соответствует коду.

## 10. Test Plan

### Characterization Tests Before Refactoring

Для каждого теста:

1. **Concurrent forceFlush + shutdown (M1)**
   - Purpose: убедиться, что промисы forceFlush завершаются (success/fail), а не зависают при гонке с shutdown.[^10]
   - Risk covered: R3 (lost promises).[^2]
   - Deterministic strategy: два потока (T1 forceFlush в цикле, T2 shutdown после задержки) + CountDownLatch и bounded timeout.[^10]
   - Fake exporter: CountingExporter или BlockingExporter.
   - Expected result: все полученные `CompletableResultCode` либо success, либо fail в пределах таймаута.
   - Timing: добавить **до** Phase 1.

2. **Multiple concurrent forceFlush (M3)**
   - Purpose: проверить корректность работы `pendingFlushes` при конкурентном доступе.
   - Risk covered: некорректное завершение части промисов.[^10]
   - Strategy: 10 потоков, CyclicBarrier для стартового барьера, проверка completion всех промисов.
   - Fake exporter: CountingExporter.
   - Expected result: все промисы завершены.
   - Timing: до Phase 1/2.

3. **Exporter shutdown hang (M4)**
   - Purpose: эксплицитно проявить риск R1.[^10]
   - Risk covered: бесконечный `exporter.shutdown()`.
   - Strategy: NeverCompleteShutdownExporter, shutdownTimeout короткий, проверка, что тест не висит бесконечно.
   - Expected result: обнаружение дефекта в текущей реализации; после фикса — bounded завершение shutdownResult.
   - Timing: до Phase 2.

4. **Worker unchecked exception path (M2)**
   - Purpose: протестировать `catch(RuntimeException unexpected)` и завершение worker/terminator.[^7][^10]
   - Risk covered: R4 (worker death без корректного shutdown).[^2]
   - Strategy: ThrowingExporter(n), проверка `workerTerminated.countDown()` и успешного shutdown.
   - Expected result: shutdownResult не висит; терминатор корректно завершает экспортер.
   - Timing: до Phase 2.

5. **Deterministic scheduleDelay export (M5)**
   - Purpose: проверить, что фоновые экспорты по истечении scheduleDelay реально работают.[^8][^10]
   - Risk covered: R8 (отсутствие детерминированного теста фонового триггера).[^2]
   - Strategy: короткий scheduleDelay, блокирующий exporter и проверка export после 2×delay.
   - Expected result: batch, содержащий спаны ниже порога batchSize, экспортируется.
   - Timing: до Phase 3.

6. **Interrupt during await path (M7)**
   - Purpose: эксплицитно прогнать путь InterruptedException в workerLoop.[^10][^7]
   - Risk covered: R2 (double‑unlock hazard) и корректность дренажа при interrupt.
   - Strategy: NeverCompletingResultCodeExporter, короткий shutdownTimeout, проверка, что shutdown завершается и droppedAfterShutdown увеличивается.[^10]
   - Expected result: нет IllegalMonitorStateException, очередь очищена, shutdownResult завершён.
   - Timing: до Phase 2/3.

7. **forceFlush after completed shutdown (M9)**
   - Purpose: проверить, что forceFlush после завершённого shutdown возвращает ofSuccess immediately.[^8][^10]
   - Risk covered: некорректный контракт forceFlush post‑shutdown.
   - Strategy: shutdown → join, затем forceFlush, проверка `isSuccess=true` и отсутствия экспорта.
   - Expected result: ofSuccess, без зависаний.
   - Timing: Phase 0.

8. **toSpanData() runtime exception path (M8)**
   - Purpose: протестировать обработку RuntimeException в `span.toSpanData()`.[^8][^10]
   - Risk covered: непротестированный путь потерь спана.
   - Strategy: ReadableSpan stub, кидающий RuntimeException; проверка одиночного WARN и продолжения работы процессора.
   - Expected result: нет падения, последующие спаны нормально экспортируются.
   - Timing: Phase 0.

9. **log throttling behavior (M6)**
   - Purpose: проверить `logExportFailureOnce`.[^8][^10]
   - Risk covered: наблюдаемость (количество WARN‑логов).
   - Strategy: Test appender, многократные fail‑экспорты, проверка точно одного WARN.
   - Expected result: ровно один WARN.
   - Timing: Phase 1.

10. **JMX getter compatibility/source compatibility**
    - Purpose: убедиться, что все шесть getters сохраняют сигнатуры и семантику.
    - Strategy: source‑compatibility тест для PlatformExportControl, проверка компиляции и runtime‑значений.
    - Timing: Phase 1 и далее при каждом рефакторинге.

11. **Benchmark gate for queue offer throughput**
    - Purpose: контролировать производительность `onEnd` при refactoring.
    - Strategy: запуск QueueOfferBenchmark до/после каждой структурной смены.[^9]
    - Timing: Phase 0+1+3.

### Priority

Высокий приоритет: M1, M2, M4, M7, M9, JMH gate.
Средний: M3, M5, M6, M8.

## 11. Risk Register

| Risk | Severity | Probability | Detection | Mitigation | Owner area |
|------|----------|------------|-----------|-----------|------------|
| R1 exporter.shutdown hang | High | Medium | M4, SDK shutdown tests | Bounded shutdown таймаут, fail shutdownResult | SRE/Platform tracing |
| R2 double-unlock hazard | High | Medium | M7, code review | Не трогать блок until C1/C2 redesign; добавить explicit tests | Java concurrency experts |
| R3 lost forceFlush promises | Medium | High | M1/M3 | Гарантированное завершение промисов при shutdown/worker exit | SpanProcessor maintainer |
| R4 worker death unchecked | Medium | Medium | M2 | Controlled shutdown on unexpected RuntimeException, alerting | SRE/SpanExporter owner |
| R6 permanent log throttle | Low | High | M6/log review | Явная документация throttle, возможно, доп.метрики | Observability architect |
| R8 scheduleDelay nondeterminism | Medium | Medium | M5 | Детерминированный тест, возможно, настройка MIN_WORKER_AWAIT_NANOS | Java concurrency expert |
| New R? from V6 (interface bugs) | High | Medium | M1–M9, новые tests | Пошаговая интеграция, feature flags на уровне code | Staff refactoring reviewer |

## 12. Final Recommendation for Principal Engineer Review

Рекомендуемый ближнесрочный путь: **V5 (Low-risk decomposition bundle)** после выполнения Phase 0, включающий извлечение конфигурации/билдера (V3), тайм‑аутного экспортера (V2) и наблюдаемостного holder'а, без изменения очереди/worker. Это улучшит архитектуру и тестируемость, не затрагивая самые рискованные участки.

Рекомендуемая долгосрочная цель: **V6 (Full internal decomposition)** после Phase 0–2, когда характеризационные тесты и targeted‑фиксы стабилизировали семантику shutdown/forceFlush, а структура класса стала понятнее благодаря малым извлечениям.

Варианты, которые нельзя выбирать сейчас:
- **V7**: отказ из‑за риска блокирующего `onEnd` и сложной реконфигурации drop‑oldest семантики на BlockingDeque/ExecutorService.[^5][^3]
- **V8**: отказ как over‑engineering, с новой внешней зависимостью и моделью, не требуемой текущими производительными целями.[^6][^9]

Важно: **не рефакторить очередь/worker (V4/V6 частично) до существования тестов M1 (concurrent forceFlush+shutdown), M2 (worker exception death), M4 (shutdown hang), M7 (interrupt path) и JMH‑baseline** — без них невозможно безопасно оценить последствия изменений.

Первый implementation PR должен содержать **только**:
- Добавление характеризационных тестов (Phase 0) и JMH gate.
- Извлечение C6/C3/C7 (V5) без изменения поведения.[^10][^1]

Что не делать:
- Не менять сигнатуры Builder и JMX‑getters.[^3]
- Не переводить worker на virtual threads без доказанной выгоды и JMH‑подтверждения.[^20]
- Не внедрять Disruptor/BlockingDeque ради «красоты»; приоритет — надёжность и предсказуемость.
- Не оптимизировать «мелкие» детали до того, как устранены R1/R3/R4.

## 13. Appendix

### Source references

- Repository md‑файлы: 00–07 (executive summary, behavior, concurrency, adjacent map, responsibilities, constraints, tests/гепсы, LLM input).[^4][^2][^9][^8][^7][^3][^10][^1]

### OpenTelemetry references

- SpanProcessor Javadoc (forceFlush/shutdown contracts).[^11][^12]
- BatchSpanProcessor Javadoc (queue overflow drop‑new).[^13]
- SpanExporter Javadoc (export/flush/shutdown).[^14]
- CompletableResultCode join timeout discussion.[^15]

### Java references

- BlockingDeque API (offer/poll semantics).[^5]
- Condition/awaitNanos semantics.[^18][^17][^16]
- Virtual threads and structured concurrency in Java 21.[^19][^20]

### Scoring rationale

Оценки по матрице опирались на:
- Анализ responsibility decomposition C1–C8.[^1]
- Ограничения refactoring‑constraints.[^3]
- Текущие риски и тест‑гепсы.[^2][^7][^10]

---

## References

1. [04-responsibility-decomposition.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/2bc6bdf9-1fc8-488a-baac-8c4ac41a20a7/04-responsibility-decomposition.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=91Fzlz1UmumRpDVyy4AvpwsAhlc%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - # Responsibility Decomposition: PlatformDropOldestExportSpanProcessor

> Decomposition is analysis...

2. [00-executive-summary.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/97610b35-3a89-4f42-ad44-649f2c518afa/00-executive-summary.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=I2KtE5fgPoNaTXQoil7xKXW8pC8%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - Field Value -------------- Target class space.br1440.platform.tracing.otel.extension.processor.Platf...

3. [06-refactoring-constraints.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/ccba0a44-ad7d-4d4f-8c04-43aacd8519b5/06-refactoring-constraints.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=EExuSIWMYMMvsTuo5xYCKSg7GcQ%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - # Refactoring Constraints: PlatformDropOldestExportSpanProcessor

> Hard constraints must not be v...

4. [07-llm-research-input.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/7ac3fde2-9f38-48b4-ac8d-96bcf4ded21b/07-llm-research-input.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=JEQ%2BF8QLadVDM0LLaneS14d1N6w%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - This file is optimized as input for Perplexity Deep Research or external LLM architectural scoring. ...

5. [Java Platform, Standard Edition Java API Reference](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingDeque.html) - declaration: module: java.base, package: java.util.concurrent, interface: BlockingDeque

6. [LMAX Disruptor User Guide](https://lmax-exchange.github.io/disruptor/user-guide/index.html) - The Disruptor is a library that provides a concurrent ring buffer data structure. It is designed to ...

7. [02-concurrency-model.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/93c8563e-f45c-41fd-8e96-abf320205f57/02-concurrency-model.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=%2Fz0eJg8%2BWBqW8ZQu3kGfAG%2B0pXk%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - Evidence-first. All line references are to PlatformDropOldestExportSpanProcessor.java. --- TITLE Con...

8. [01-current-behavior.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/90c0583c-2d23-4918-a1bc-437a9f1b3c8b/01-current-behavior.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=8%2BC9wEGCYcSaCcW7PnB%2FihzCQnQ%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - Evidence-first. All line references are to platform-tracing-otel-extensionsrcmainjavaspacebr1440plat...

9. [03-adjacent-code-map.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/b9c2a0ea-1916-4123-a8a6-7e9a3460e3c7/03-adjacent-code-map.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=IrNZJijK%2B2kYG1QpFiuffYvNnCw%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - Every entry is evidence-based. Speculative entries are labeled SPECULATION. --- TITLE Adjacent Code ...

10. [05-test-coverage-and-gaps.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/88bd7607-aee4-4478-abdb-19edc33e25c1/05-test-coverage-and-gaps.md?AWSAccessKeyId=ASIA2F3EMEYEUPCHYZ46&Signature=PMwruYFc7E8XZDa9PVU3VLlKt0c%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPr%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIENaMQFNSqF%2F9088SsEaA5vXYtOCQl7yU8Zyut32rcwLAiEAqoulGh1oL4ri6zphnJeY%2Bap4lj52btJV1%2FeI0d9P4y8q%2FAQIw%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw2OTk3NTMzMDk3MDUiDEDzC0A0XYQ8Zk3MwyrQBNMNN%2Fbvqw%2F5etwRcSQMDo1iCaRcpXljK4Uuapk7UpUWFJ8xy1GtQJm%2Bgk9PW%2FSqqdrES8eEhKE6xL9RQz04HWsmczKb4u758pA1m736BP00XV96VyQAEK77qjzRR7pZYdh%2F69qqGQQYVfY%2FqUI%2FoOxNkcHpoeJ16ljVg5m2J84CSduTB0bFyFNBFmpRHkpk1a1qvFSn%2FA2U0JPtUjB%2FN4Jk6jwBneyCU269pOhuxZ0kD332HD4qGwk4Q2K9dGOWsirfzw63sSqrOWyERGakyAgRRCHPQfk%2FWNQLPDZSnnObSVEcv75UKP%2BmmYBmpVfpk%2FS5WD0lThFZBBC9%2FI87cUdnnkvLq2PhE7Yq8KGVLVrVp0EuI8UA39ZtUtPbmxVVXd46QE3S8n2ZYZUZOQDZkW%2F4wX8nNajApzCyRQldMkPgsO7aBsSAFkSSJF1sIoIFLgulVXrBJRY2mN9IZwxNDgzc1jwrkV7zQJe5jfvJehWqkmX1I86uZnbFjNWw2ZK0JBXHhjaZZ4HYzVttoAwQI7dL6wrVPaSN72Mz29l%2FwOTcimhffHdQch9gptVKaXLfX%2FoLrMDuIfhuCejm5VXrPUxpo4g53DGil2fRwXYNKxaVr%2Fnej8gzWrHi%2Fx3y0%2F53KWw3IuGCoP6w6T5587xx7aHawIFFmreeyuYPc%2F3RgjH17QhCjCis5xSjrFZWH8JNztPeWCl6e9FUWZAdL%2FoqUhYNCQLHrCfqs6l0LK8aom6pQoZobwlRqIt97KsgRPVEPX843803OWbdDmv0VnVt0i4wkaGO0gY6mAHwmrzTfaDzVbC2NPUrvqJAZm%2FTVK0tM8X%2Bahbq4u2gyKUSKlWJlVyfhGkvK3%2F65sCUIXV4x%2FMsp2cstocYoWuu0N%2B5W6IUSVVV%2BVnj%2Fjpuw9jpq7HclbbefRSCzsjRos1755%2BPAum7l7DwwGcsp6LsXON1WaP6m60p0tkOohPzeyxT2DymkndDUhcLY40v9FXdv9XP%2BXf%2BJQ%3D%3D&Expires=1782816356) - # Test Coverage and Gaps: PlatformDropOldestExportSpanProcessor

> Existing tests are described ba...

11. [opentelemetry-java/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/SpanProcessor.java at main · open-telemetry/opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/SpanProcessor.java) - OpenTelemetry Java SDK. Contribute to open-telemetry/opentelemetry-java development by creating an a...

12. [SpanProcessor (OpenTelemetry SDK For Tracing)](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/SpanProcessor.html)

13. [Class BatchSpanProcessor](https://www.javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.16.0/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.html) - declaration: package: io.opentelemetry.sdk.trace.export, class: BatchSpanProcessor

14. [SpanExporter (OpenTelemetry SDK For Tracing)](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/export/SpanExporter.html)

15. [CompletableResultCode.join() suprisingly fail()s on timeout. #2942](https://github.com/open-telemetry/opentelemetry-java/issues/2942) - It also reduces the flexibility of the method as calling fail() in the catch(TimeoutException) is ea...

16. [Condition (Java SE 11 & JDK 11 ) - Oracle Help Center](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/locks/Condition.html)

17. [Java JUC教程第7章：Condition条件变量 - 小傅哥](https://gaga.plus/app/juc/chapter-07-condition/) - Java JUC教程第7章详细介绍Condition条件变量的使用方法、await和signal机制、生产者消费者模式实现。

18. [ICondition.AwaitNanos(Int64) 메서드 (Java.Util.Concurrent.Locks)](https://learn.microsoft.com/ko-kr/dotnet/api/java.util.concurrent.locks.icondition.awaitnanos?view=net-android-34.0) - 현재 스레드가 신호를 보내거나 중단되거나 지정된 대기 시간이 경과할 때까지 대기합니다.

19. [Java 21 Deep Dive: How Virtual Threads and Structured ...](https://java-news.net/java-21-deep-dive-how-virtual-threads-and-structured-concurrency-are-reshaping-the-jvm-ecosystem) - Java 21 has arrived, and it's not just another incremental update; it's a landmark Long-Term Support...

20. [Java 21 Virtual Threads and Structured Concurrency: Complete ...](https://java.elitedev.in/java/java-21-virtual-threads-and-structured-concurrency-complete-performance-guide-with-examples-736d7202/) - Master Java 21's Virtual Threads and Structured Concurrency with this comprehensive guide. Learn imp...

21. [Uses of Class com.lmax.disruptor.RingBuffer](https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/class-use/RingBuffer.html)

22. [InMemoryMetricExporter (OpenTelemetry SDK Testing utilities)](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-testing/1.27.0/io/opentelemetry/sdk/testing/exporter/InMemoryMetricExporter.html) - declaration: package: io.opentelemetry.sdk.testing.exporter, class: InMemoryMetricExporter

23. [Lock (Java Platform SE 8 ) - Oracle Help Center](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Lock.html) - A lock is a tool for controlling access to a shared resource by multiple threads. Commonly, a lock p...

