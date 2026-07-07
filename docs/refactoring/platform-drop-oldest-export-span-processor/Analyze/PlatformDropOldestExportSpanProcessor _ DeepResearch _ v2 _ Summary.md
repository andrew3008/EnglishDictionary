<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## 1. Executive Summary

Внешнее исследование подтверждает, что текущая архитектура `PlatformDropOldestExportSpanProcessor` правильно опирается на контракты OpenTelemetry Java SDK: `SpanProcessor`, `SpanExporter`, `BatchSpanProcessor` и `CompletableResultCode` действительно работают через асинхронные результаты, `forceFlush`/`shutdown` являются ключевыми точками жизненного цикла, а batch‑процессор использует ограниченную очередь с таймаутами экспорта.[^1][^2][^3]
Исследование также подтверждает, что стандартный `BatchSpanProcessor` реализует политику drop‑new при переполнении очереди и не поддерживает drop‑oldest, поэтому не может заменить кастомный процессор без потери требований платформы.[^4][^5][^6]
Главные импликации для рефакторинга: нельзя переходить на stock BSP, нужно сохранять неблокирующий `onEnd` и запрет вызова `exporter.export()` под lock, а улучшения вокруг shutdown/forceFlush должны следовать продакшен‑паттернам bounded shutdown, завершения `CompletableResultCode` и понятного учёта потерь через счётчики и лог‑throttling.[^7][^8][^1]

## 2. OpenTelemetry Contract Findings

### SpanProcessor

- **Contract.** `SpanProcessor` предоставляет синхронные хуки `onStart`/`onEnd` и методы `isStartRequired`/`isEndRequired`, а также `forceFlush()` и `shutdown()`, которые должны обеспечить обработку всех span‑событий до завершения и не бросать исключения.[^9][^2]
- **Source.** Javadoc `io.opentelemetry.sdk.trace.SpanProcessor` и спецификация SDK trace.[^2][^9]
- **Implication.** Реализация процессора должна гарантировать, что при `shutdown` уже завершённые спаны либо экспортированы, либо явно признаны потерянными, а `forceFlush` синхронно инициирует экспорт накопленных спанов и возвращает результат, который завершится.[^10][^2]
- **Refactoring constraint.** Нельзя превращать `shutdown()` или `forceFlush()` в потенциально бесконечные операции или оставлять возвращаемый `CompletableResultCode` незавершённым; повторные вызовы после shutdown должны вести себя как no‑op, что уже закреплено в вашем классе и ADR.[^11][^10]


### BatchSpanProcessor

- **Contract.** `BatchSpanProcessor` буферизует спаны в ограниченной очереди (`maxQueueSize`), экспортирует их порциями (`maxExportBatchSize`) по таймеру `scheduledDelayMillis` или при forceFlush и гарантирует, что в один момент времени выполняется только один экспорт.[^5][^1]
- **Source.** GitHub `BatchSpanProcessor.java` и официальные Java SDK docs.[^3][^5]
- **Implication.** BSP — референсная реализация batch‑логики, к которой вы сейчас частично выравниваетесь по параметрам (queue size, batch size, delay, export timeout).[^5][^3]
- **Refactoring constraint.** При рефакторинге нельзя нарушать паритет с BSP по конфиг‑ключам (`otel.bsp.*`), но при этом вы не можете полностью перейти на BSP из‑за отличающейся overflow‑политики.


### BatchSpanProcessor Overflow Semantics

- **Does stock BSP support drop-oldest?** Нет: при достижении `maxQueueSize` BSP отбрасывает **новые** спаны (drop‑new), что зафиксировано в коде и обсуждениях — при переполнении очереди новые `onEnd` приводят к drop и эмиссии метрики.[^6][^4][^5]
- **Evidence.**
    - Issue «BatchSpanProcessor drops span» описывает, что при заполнении in‑memory queue BSP просто дропает span и эмитит метрическую точку, без логов.[^4]
    - Руководство по tuning batch‑процессора в Node.js SDK аналогично объясняет: при переполнении новые спаны silently dropped, пока очередь не разгрузится.[^6]
- **Can it replace this custom processor?** Нет: это нарушит требование платформы «drop‑oldest, newest survives overflow», которое у вас уже формализовано в ADR и тестах.[^12][^6]
- **If not, why?**
    - BSP не предоставляет конфигурируемую overflow‑политику; drop‑new жёстко зашит в реализацию.[^4][^5]
    - Ваши JMX‑счётчики и тесты проверяют именно drop‑oldest контракт и баланс `dropped + exported == TOTAL`, чего BSP из коробки не даёт.[^6][^4]


### SpanExporter

- **Contract.** `SpanExporter` определяет методы `export(spans)`, `flush()` и `shutdown()`, все возвращают `CompletableResultCode`, который должен быть завершён при завершении операции.[^13][^1]
- **Source.** Javadoc `SpanExporter` и реализации `JaegerThriftSpanExporter`, `LoggingSpanExporter`, AzureMonitorExporter.[^14][^15][^1][^13]
- **Implication.** Ваш процессор должен вызывать `export`/`shutdown` так, чтобы не блокировать producer threads, но корректно обрабатывать их `CompletableResultCode` (join с таймаутом, success/fail) и учитывать ошибки через счётчики.[^1][^7]
- **Refactoring constraint.** Нельзя вызывать `exporter.export()` под lock, который используется в `onEnd`; любые изменения очереди/воркера должны сохранять этот invariant.


### CompletableResultCode

- **Contract.** `CompletableResultCode` — утилита, возвращаемая `export`/`flush`/`shutdown`; метод `join(timeout, unit)` при таймауте может помечать результат как fail, что отличается от стандартного `CompletableFuture.get(timeout)`.[^7][^3]
- **Source.** Issue `CompletableResultCode.join() suprisingly fail()s on timeout` и SDK docs.[^3][^7]
- **Implication.** Интерпретация таймаута как failure легитимирует ваш контракт `exportTimeouts ⊆ exportFailures`: таймауты должны учитываться и как timeout, и как failure.[^1][^7]
- **Refactoring constraint.** Любые изменения таймаут‑логики экспорта должны сохранять это отношение счётчиков и не превращать таймауты в «безучётные» длительные ожидания.


### forceFlush / shutdown (мульти‑языковой контекст)

- **Contract.** В C++/Ruby SDK `SpanProcessor.forceFlush(timeout)` и `Shutdown(timeout)` либо экспортируют все pending spans с учётом таймаута, либо немедленно возвращают успех, причём после Shutdown повторные вызовы возвращают успех без работы.[^10][^11]
- **Source.** C++ SpanProcessor docs, Ruby SpanProcessor docs.[^11][^10]
- **Implication.** Ваше поведение «forceFlush после shutdown → ofSuccess immediately» согласуется с этим мульти‑языковым контрактом и должно быть сохранено.[^11][^3]
- **Refactoring constraint.** Вы можете добавлять bounded timeout в shutdown/forceFlush (например, вокруг exporter.shutdown), но не должны менять пост‑shutdown semantics этих методов.


## 3. BatchSpanProcessor Overflow Semantics

- **Does stock BSP support drop-oldest?** Нет, BSP реализует drop‑new: при переполнении очереди новые спаны отбрасываются, очередь остаётся неизменной, и потери фиксируются через метрику `maxQueueSize reached`.[^5][^4][^6]
- **Evidence.**
    - Исходники `BatchSpanProcessor.java` показывают логику, где при превышении `maxQueueSize` новый span не добавляется в очередь, а drop фиксируется метрикой.[^4][^5]
    - Руководство по Node.js BatchSpanProcessor явно говорит: «If the queue has more than maxQueueSize spans, new spans are dropped».[^6]
- **Can it replace this custom processor?** Нет: требование платформы — drop‑oldest (сохранение самых новых спанов), а BSP при overflow сохраняет старые и отбрасывает новые, что приводит к потере последних span‑событий при бурстах или медленном экспортере.[^12][^6]
- **If not, why?**
    - BSP не изменяем по overflow‑политике без форка SDK.[^5]
    - Ваши JMX‑счётчики и тесты завязаны на drop‑oldest семантику и баланс счётчиков; переход на BSP нарушит эти инварианты и договорённость с SRE по taxonomy потерь.[^4][^6]


## 4. Java Concurrency Findings

### ReentrantLock / Condition.awaitNanos

- **Что предоставляет.** `ReentrantLock` даёт явное управление lock’ом, `Condition.awaitNanos` освобождает lock, ждёт сигнал/прерывание/таймаут, затем заново захватывает lock и возвращает «остаточное» время; допускаются spurious wakeups, поэтому использование должно быть обёрнуто в while‑цикл с проверкой условия.[^16]
- **Whether it helps.** Это хорошо соответствует вашей модели очереди/воркера: один lock, одна Condition, `awaitNanos` для реализации scheduleDelay/forceFlush/shutdown событий, при этом exporter вызывается вне критической секции.[^17][^16]
- **Whether it risks behavior change.** Нестандартный паттерн «явный unlock в catch InterruptedException + guard isHeldByCurrentThread в finally» создаёт риск double‑unlock или утечки lock при любом рефакторинге блока.[^16][^17]
- **Recommendation.** Сохранять ReentrantLock/Condition, но при декомпозиции очереди/воркера избавиться от ручного unlock вне finally и использовать более стандартный шаблон `lock → awaitNanos → finally unlock`.


### BlockingDeque / LinkedBlockingDeque

- **Что предоставляет.** `BlockingDeque` реализует двустороннюю блокирующую очередь с методами `offerLast`, `pollFirst` и вариантами с таймаутами.[^18]
- **Whether it helps.** Может упростить реализацию drop‑oldest (через `pollFirst`+`offerLast`) и предоставить готовую bounded очередь.[^18]
- **Whether it risks behavior change.** По умолчанию `offerLast` может блокировать при заполненной очереди; использование блокирующих методов нарушит ваш invariant «onEnd не блокирует приложение».[^19][^18]
- **Recommendation.** Не переходить целиком на BlockingDeque (V7) как замену lock+ArrayDeque; рассматривать её только как внутренний примитив в extracted queue wrapper при строгом использовании неблокирующих операций.


### ArrayBlockingQueue

- **Что предоставляет.** Bounded FIFO очередь с блокирующими и неблокирующими операциями; поддерживает `drainTo` для группового дренажа.[^20]
- **Whether it helps.** Может дать готовый bounded FIFO и массовый дренаж, но не предоставляет прямого drop‑oldest при overflow и склонна к блокирующему `put`/`take`.[^20]
- **Whether it risks behavior change.** Замена на ArrayBlockingQueue может привести к блокировкам в `onEnd` и ухудшению latency, а массовый `drainTo` может сложнее контролироваться в сочетании с вашим worker/shutdown.[^20]
- **Recommendation.** Не использовать ArrayBlockingQueue напрямую в этом компоненте.


### ExecutorService

- **Что предоставляет.** Пулы потоков для выполнения задач, в том числе на базе virtual threads в Java 21.[^21][^22]
- **Whether it helps.** Может унифицировать жизненный цикл worker‑потока, но текущая модель уже использует один daemon‑thread и не требует сложного пула.[^17]
- **Whether it risks behavior change.** Перевод worker на ExecutorService усложнит shutdown и observability и мало что даст для производительности.[^23]
- **Recommendation.** Оставить текущий dedicated daemon‑thread, не переходить на ExecutorService для этого компонента.


### Virtual threads / structured concurrency

- **Что предоставляет.** Virtual threads — лёгкие потоки, structured concurrency даёт API (`StructuredTaskScope`) для безопасного управления группами задач.[^21][^23]
- **Whether it helps.** Полезно для множества concurrent I/O‑операций; для вашего single‑worker‑процессора выгода минимальна.[^22][^21]
- **Whether it risks behavior change.** synchronized/locks могут «pin» виртуальные потоки к carrier‑thread, что снижает выгоду и усложняет reasoning.[^24][^21]
- **Recommendation.** Не использовать virtual threads/StructuredTaskScope в текущем рефакторинге этого класса.


### Ring buffers / Disruptor-style queues

- **Что предоставляет.** LMAX Disruptor — высокопроизводительный кольцевой буфер (`RingBuffer`) с гибкими WaitStrategy и поддержкой single/multi‑producer; рассчитан на очень низкую latency и высокую throughput.[^25][^26]
- **Whether it helps.** Теоретически даёт bounded очередь и высокую производительность, но требует полной переработки модели producer/consumer и shutdown.[^26][^25]
- **Whether it risks behavior change.** Меняет архитектуру на event‑driven, усложняет конфигурацию overflow/backpressure и добавляет внешнюю зависимость.[^25]
- **Recommendation.** Отказаться от Disruptor‑стиля для этого процессора; это будет over‑engineering относительно текущих требований.


## 5. Production Reliability Patterns

- **Bounded shutdown.** Во внешних материалах рекомендуется ограничивать shutdown по времени (через таймаут параметра или завершение `CompletableResultCode`), чтобы SDK не зависал навсегда при проблемах exporter.[^8][^3]
- **Exporter shutdown timeout.** Реальные exporters (Jaeger, Azure Monitor) могут завершать shutdown быстро или не гарантировать доставку всех спанов; issues показывают, что senders не всегда graceful, поэтому bounded timeout вокруг `exporter.shutdown()` — хорошая практика.[^14][^8]
- **forceFlush completion guarantees.** В мульти‑языковых SDK `forceFlush` должен либо попытаться экспортировать накопленные спаны, либо сразу вернуть успех, но не зависать; это поддерживает ваш контракт с `CompletableResultCode` и forceFlush promises.[^10][^11]
- **Worker death handling.** Производственные системы требуют явной обработки death‑worker: либо перезапуск, либо контролируемый shutdown и сигнализация; в Java SDK есть баг‑репорты по shutdown race, что подтверждает важность явной диагностики.[^27][^8]
- **Logging throttling.** BSP сейчас плохо логирует drop spans и разработчики просят добавить WARN‑логи; при этом важно не зафлудить лог при массовых потерях, поэтому подход вроде `logExportFailureOnce` (один WARN, далее только счётчики/метрики) соответствует best‑practice.[^6][^4]
- **Counters and metrics.** Метрики и счётчики (drop spans, export failures, retries) — основной способ отслеживать здоровье пайплайна; BSP эмитит метрики при drop, но не логирует по умолчанию, что делает ваши JMX‑счётчики особенно ценными для диагностики.[^4][^6]


## 6. Applicability Matrix

| Техника | Preserves drop-oldest | Preserves non-blocking onEnd | Reduces concurrency risk | Complexity | Performance risk | Production suitability |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| Ручной lock + ArrayDeque | Да | Да | Нет (нестандартный unlock) | Средняя | Низкий (JMH‑baseline) | Высокая (уже в проде) |
| Extracted queue wrapper (C1) | Да | Да | Да, если убрать ручной unlock | Высокая | Потенциально низкий | Высокая при осторожном PR |
| BlockingDeque | Да | Под угрозой (offer может блокировать) | Частично | Средняя–высокая | Средний | Средняя, требует осторожности |
| ExecutorService worker | Н/Д | Да | Частично | Средняя | Низкий–средний | Средняя |
| Virtual threads / Structured concurrency | Да | Да, но locks могут pin’ить | Не напрямую | Высокая | Неочевидный | Низкая для этого класса |
| Ring buffer / Disruptor | Да | Да при правильной конфигурации | Да, но сложно | Очень высокая | Неопределённый | Низкая, over‑engineering |
| Stock BSP adapter (drop-new) | Нет | Да | Да | Средняя | Низкий | Низкая (нарушает требования) |

## 7. Final External Research Conclusions

1. Стандартный `BatchSpanProcessor` реализует drop‑new overflow, поэтому он принципиально не подходит для замены вашего кастомного drop‑oldest процессора.[^5][^6][^4]
2. Контракты `SpanProcessor.forceFlush`/`shutdown` в Java/C++/Ruby требуют bounded времени и немедленного no‑op поведения после shutdown; ваша текущая семантика согласуется с этим и должна оставаться неизменной.[^2][^10][^11]
3. `SpanExporter` и `CompletableResultCode` предполагают, что таймауты экспортов сигнализируют failure; сохранение отношения `exportTimeouts ⊆ exportFailures` — важный наблюдаемостный инвариант, который нельзя нарушать.[^7][^1]
4. Bounded shutdown вокруг `exporter.shutdown()` — необходимое улучшение: issues по SDK показывают, что senders могут зависать и не гарантировать graceful shutdown.[^8][^14]
5. ReentrantLock/Condition.awaitNanos остаются подходящими низкоуровневыми примитивами для очереди/воркера; refactoring должен упростить их использование, убрав опасный manual unlock паттерн.[^16]
6. BlockingDeque/ArrayBlockingQueue не дают готового неблокирующего drop‑oldest; их применение требует кастомного wrapper’а и может легко нарушить контракт неблокирующего `onEnd`.[^18][^20]
7. Virtual threads и structured concurrency — мощные фичи Java 21, но не дают практической выгоды для single‑worker‑компонента; их внедрение в этот класс не оправдано.[^22][^23][^21]
8. Disruptor/ring buffer архитектуры несут сложность и внешнюю зависимость; для вашего агента это явный over‑engineering по сравнению с текущим уровнем требований и производительности.[^26][^25]
9. Production‑grade telemetry компоненты обычно реализуют bounded shutdown, exporter shutdown timeout, гарантии завершения forceFlush, обработку death‑worker и throttled logging; эти паттерны следует перенести в ваш процессор, но не ценой нарушения его специфических invariants.[^28][^8][^4]
10. Любой рефакторинг очереди/воркера должен быть поэтапным и сопровождаться характеризационными тестами и JMH‑gate, а слабые варианты вроде «заменить всё на BlockingDeque или Disruptor» следует считать вне рамок безопасного пути.

## 8. Source Appendix

Полный отчёт «platform-drop-oldest-external-research-report» содержит детализированные выдержки из всех использованных источников, включая:

- Javadoc и GitHub‑исходники OpenTelemetry Java (`SpanProcessor`, `BatchSpanProcessor`, `SpanExporter`, `CompletableResultCode`).[^2][^3][^1][^7][^5]
- JDK‑документацию по `Condition`, `BlockingDeque`, `ArrayBlockingQueue` и другим concurrency‑примитивам.[^16][^20][^18]
- Официальные и полупервичные материалы по Java 21 virtual threads и structured concurrency.[^23][^21][^22]
- Официальные LMAX Disruptor руководства и Javadoc по `RingBuffer`.[^25][^26]
- Описание поведения разных exporters (Jaeger, Logging, Azure Monitor).[^15][^13][^14]
- Issues и статьи по shutdown/forceFlush/overflow поведению в OpenTelemetry SDK.[^28][^8][^6][^4]

***

Полный, сильно детализированный отчёт уже создан как отдельный Markdown‑документ; его можно использовать как «внешний исследовательский» вход для ADR/рефакторингового плана и сопоставлять с вашим репозиторным анализом.
<span style="display:none">[^29][^30][^31][^32][^33][^34][^35][^36][^37][^38][^39][^40][^41][^42][^43][^44][^45][^46][^47][^48][^49][^50][^51][^52][^53][^54][^55][^56][^57][^58][^59]</span>

<div align="center">⁂</div>

[^1]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/export/SpanExporter.html

[^2]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk/0.7.1/io/opentelemetry/sdk/trace/SpanProcessor.html

[^3]: https://opentelemetry.io/docs/languages/java/sdk/

[^4]: https://github.com/open-telemetry/opentelemetry-java/issues/7103

[^5]: https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java

[^6]: https://oneuptime.com/blog/post/2026-02-06-fix-nodejs-batch-processor-queue-full/view

[^7]: https://github.com/open-telemetry/opentelemetry-java/issues/6160

[^8]: https://opentelemetry-cpp.readthedocs.io/en/latest/otel_docs/classopentelemetry_1_1sdk_1_1trace_1_1SpanProcessor.html

[^9]: https://opentelemetry.io/docs/specs/otel/trace/sdk/

[^10]: 01-current-behavior.md

[^11]: 05-test-coverage-and-gaps.md

[^12]: 06-refactoring-constraints.md

[^13]: 00-executive-summary.md

[^14]: 07-llm-research-input.md

[^15]: https://javadoc.io/static/io.opentelemetry/opentelemetry-exporters-logging/0.9.1/io/opentelemetry/exporters/logging/LoggingSpanExporter.html

[^16]: https://javadoc.io/static/io.opentelemetry/opentelemetry-exporter-jaeger-thrift/1.10.0-rc.2/io/opentelemetry/exporter/jaeger/thrift/JaegerThriftSpanExporter.html

[^17]: https://learn.microsoft.com/en-us/java/api/com.azure.opentelemetry.exporters.azuremonitor.azuremonitorexporter?view=azure-java-preview

[^18]: https://github.com/open-telemetry/opentelemetry-java/issues/2942

[^19]: 02-concurrency-model.md

[^20]: https://open-telemetry.github.io/opentelemetry-ruby/opentelemetry-sdk/v1.1.0/OpenTelemetry/SDK/Trace/SpanProcessor.html

[^21]: 03-adjacent-code-map.md

[^22]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Condition.html

[^23]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingDeque.html

[^24]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ArrayBlockingQueue.html

[^25]: https://java.elitedev.in/java/java-21-virtual-threads-and-structured-concurrency-complete-performance-guide-with-spring-boot-inte-5245cb93/

[^26]: https://developers.redhat.com/articles/2023/09/21/whats-new-developers-jdk-21

[^27]: https://developers.redhat.com/articles/2023/10/03/beyond-loom-weaving-new-concurrency-patterns

[^28]: https://www.youtube.com/watch?v=nR2NsVF6uhc

[^29]: https://www.youtube.com/watch?v=vxyQh5gr9us

[^30]: https://lmax-exchange.github.io/disruptor/user-guide/index.html

[^31]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/class-use/RingBuffer.html

[^32]: https://github.com/open-telemetry/opentelemetry-java/issues/6827

[^33]: https://oneuptime.com/blog/post/2026-02-06-otel-sdk-shutdown-java-spring-boot/view

[^34]: https://oneuptime.com/blog/post/2026-02-06-fix-max-queue-size-reached-warnings-opentelemetry/view

[^35]: https://opentelemetry.io/ko/docs/languages/java/sdk/index.md

[^36]: https://github.com/open-telemetry/opentelemetry-swift/blob/main/Sources/OpenTelemetrySdk/Trace/SpanProcessors/BatchSpanProcessor.swift

[^37]: https://www.youtube.com/watch?v=QxxG66eQoTc

[^38]: https://medium.com/@victorhsr/java-21-structured-concurrency-powering-data-orchestration-with-virtual-threads-and-scopes-739781b1817d

[^39]: 04-responsibility-decomposition.md

[^40]: https://cloud.tencent.com/developer/article/1635773

[^41]: https://java-news.net/java-21-deep-dive-how-virtual-threads-and-structured-concurrency-are-reshaping-the-jvm-ecosystem

[^42]: https://java.elitedev.in/java/java-21-virtual-threads-and-structured-concurrency-complete-performance-guide-with-examples-736d7202/

[^43]: https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/SpanProcessor.java

[^44]: https://opentelemetry.io/ja/docs/languages/java/sdk/index.md

[^45]: https://docs.spring.io/spring-boot/3.3/api/java/org/springframework/boot/actuate/autoconfigure/tracing/SpanProcessors.html

[^46]: https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/SimpleSpanProcessor.java

[^47]: https://www.javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.16.0/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.html

[^48]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/locks/Condition.html

[^49]: https://www.javalld.com/learn/condition-await

[^50]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/SpanProcessor.html

[^51]: https://docs.oracle.com/javase/jp/6/api/java/util/concurrent/locks/Condition.html

[^52]: https://gaga.plus/app/juc/chapter-07-condition/

[^53]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-testing/1.27.0/io/opentelemetry/sdk/testing/exporter/InMemoryMetricExporter.html

[^54]: https://learn.microsoft.com/en-us/dotnet/api/java.util.concurrent.locks.abstractqueuedsynchronizer.conditionobject.awaitnanos?view=net-android-35.0

[^55]: https://javadoc.io/static/io.opentelemetry/opentelemetry-exporters-otlp/0.9.1/io/opentelemetry/exporters/otlp/OtlpGrpcSpanExporter.html

[^56]: https://learn.microsoft.com/ja-jp/dotnet/api/java.util.concurrent.locks.icondition.awaitnanos?view=net-android-34.0

[^57]: https://javadoc.io/static/io.opentelemetry/opentelemetry-exporter-prometheus/1.36.0-alpha/io/opentelemetry/exporter/prometheus/PrometheusMetricReader.html

[^58]: https://learn.microsoft.com/ko-kr/dotnet/api/java.util.concurrent.locks.icondition.awaitnanos?view=net-android-34.0

[^59]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Lock.html

