## 1. Executive Summary

Внешние источники подтверждают, что текущая архитектура `PlatformDropOldestExportSpanProcessor` корректно опирается на контракт OpenTelemetry: `SpanProcessor`/`SpanExporter` реализуют `forceFlush`/`shutdown` как операции, возвращающие `CompletableResultCode`, а `BatchSpanProcessor` — стандартный референсный процессор с batch‑очередью и механизмами таймаута экспорта.[^1][^2][^3]
В то же время внешние источники показывают, что стандартный `BatchSpanProcessor` реализует drop‑new overflow‑семантику (новые спаны отбрасываются при заполнении очереди), а не drop‑oldest, и что его shutdown/forceFlush‑поведение не даёт гибких гарантий для пер‑процессора.[^4][^5][^6]
Основные импликации для рефакторинга: класс нельзя заменить BSP без потери drop‑oldest, любые изменения доставки спанов должны уважать контракт неблокирующего `onEnd` и вызова `exporter.export()` вне lock, а улучшения вокруг shutdown/forceFlush должны следовать паттернам bounded shutdown и завершения `CompletableResultCode`, которые применяются в других языках и экспортерах.[^7][^8][^1]

## 2. OpenTelemetry Contract Findings

### SpanProcessor

**Contract.** В ранних версиях Java SDK `SpanProcessor` определяет методы `onStart`, `onEnd`, `isStartRequired`, `isEndRequired`, `forceFlush`, `shutdown`, причём `shutdown` обязан обеспечить обработку всех span‑событий до возврата, а `forceFlush` синхронно обрабатывает все незавершённые события на calling thread. В спецификации SDK по трейсингу для разных языков указано, что `SpanProcessor` ответственен за batching и экспорт, а `forceFlush`/`shutdown` должны обеспечить экспорт всех завершённых спанов.[^9][^2][^8]

**Source.** Javadoc `io.opentelemetry.sdk.trace.SpanProcessor` для Java, и документация SDK/спецификации трейсинга.[^2][^9]

**Implication.** Любая реализация, включая `PlatformDropOldestExportSpanProcessor`, должна гарантировать, что при `shutdown` все уже завершённые спаны либо экспортированы, либо явным образом признаны потерянными (через счётчики/drop‑metrics), а `forceFlush` из вызывающего потока не должен бросать исключения и должен возвращать результат, который завершится в разумное время.[^10][^11]

**Refactoring constraint.** Нельзя менять семантику `forceFlush()`/`shutdown()` так, чтобы они становились потенциально бесконечными либо не завершающимися; любые внутренние изменения должны гарантировать, что возвращаемый `CompletableResultCode` всегда завершается (успех/ошибка) и что повторные вызовы после shutdown возвращают немедленный успех/но‑оп — это уже зафиксировано и в репозитории.[^12][^10]

### BatchSpanProcessor

**Contract.** `BatchSpanProcessor` — встроенный процессор, который буферизует спаны в ограниченной очереди (`maxQueueSize`) и экспортирует их батчами по `maxExportBatchSize`, либо по таймеру `scheduledDelayMillis`, либо по forceFlush; он гарантирует, что только один экспорт выполняется в каждый момент времени и что операции `export()`/`flush()` возвращают `CompletableResultCode`.[^5][^1]

**Overflow behavior.** По обсуждениям в issues и по коду разных реализаций (Java, Node.js) при переполнении очереди BSP отбрасывает **новые** спаны (drop‑new): когда количество ожидающих спанов превышает `maxQueueSize`, очередной `onEnd` приводит к silent drop нового span’а и эмиссии метрики/diagnostic.[^6][^4][^5]

**Source.** GitHub `BatchSpanProcessor.java` и issues/документация, описывающие предупреждение `Dropping span because maxQueueSize reached`.[^4][^5][^6]

**Implication.** Стандартный BSP не поддерживает drop‑oldest (poll head при overflow), а реализует drop‑new; это соответствует ADR в репозитории, где описано, что BSP не пригоден для требований платформы drop‑oldest.[^13][^14]

**Refactoring constraint.** Нельзя заменить `PlatformDropOldestExportSpanProcessor` прямой конфигурацией BSP, даже при подстройке параметров; любые адаптеры вокруг BSP должны эмулировать drop‑oldest сами, что приводит к дополнительной сложности и не снимает необходимости кастомного процессора.

### SpanExporter

**Contract.** `SpanExporter` — интерфейс, предоставляющий методы `export(Collection<SpanData>)`, `flush()`, `shutdown()`, все возвращают `CompletableResultCode`, который завершается при завершении операции. BatchSpanProcessor гарантирует, что одновременно выполняется только один экспорт, но сам exporter может реализовать параллельность, если используется простая реализация.[^15][^1]

**Source.** Javadoc `SpanExporter`, реализации `JaegerThriftSpanExporter`, `LoggingSpanExporter`, внешние экспортеры (Azure Monitor).[^16][^17][^1][^15]

**Implication.** `PlatformDropOldestExportSpanProcessor` при рефакторинге должен уважать семантику: не блокировать producer threads на exporter I/O, но корректно вызывать `export()`/`shutdown()` и обрабатывать их `CompletableResultCode` (join/таймаут, success/fail).[^10][^12]

**Refactoring constraint.** Внутренняя логика таймаута экспортов и shutdown’а должна оставаться совместимой с тем, что exporters могут быть медленными, ретрайть, или завершать shutdown как success даже при отмене внутренних отправок; нужно избегать бесконечных join’ов и корректно учитывать таймауты.[^1][^7]

### CompletableResultCode

**Contract.** `CompletableResultCode` — утилита, инкапсулирующая async результат и предоставляющая методы `succeed()`, `fail()` и `join(timeout, unit)`. Обсуждение issue #2942 показывает, что `join` с таймаутом может помечать результат как failure при истечении времени, что отличается от `CompletableFuture.get(timeout)` и важно для semantics таймаутов.[^18]

**Source.** Issue #2942 и фрагменты sdk‑docs.[^3][^18]

**Implication.** Таймаутные экспорт‑операции должны инкрементировать counters `exportTimeouts` и `exportFailures` согласованно, учитывая, что `join` при таймауте завершает результат с fail; это соответствует текущему коду процессора, который учитывает таймаут как подмножество failures.[^12][^10]

**Refactoring constraint.** Нельзя менять интерпретацию таймаута `export()` так, чтобы `exportTimeouts` перестали быть подмножеством `exportFailures`, и нельзя бесконтрольно использовать `join` без таймаута в shutdown‑пути — это создаёт риск R1, уже отмеченный в репозитории.[^19][^13]

### forceFlush/shutdown общие выводы

**Contract.** В разных языках (Java, C++, Ruby) `SpanProcessor.forceFlush` и `shutdown` либо принимают таймаут, либо возвращают код, который должен быть завершён в bounded времени; после shutdown повторные вызовы возвращают немедленный успех и не выполняют реальную работу.[^8][^20][^3]

**Implication.** Реализация в платформе (forceFlush после shutdown → ofSuccess immediately) соответствует мульти‑языковой практике; любые изменения forceFlush/shutdown должны сохранять эту семантику, но могут добавлять таймауты и fail‑поведение при зависании exporter.[^10][^12]

**Refactoring constraint.** Добавление shutdown‑таймаута для exporter и усиление гарантии завершения forceFlush‑промисов (no hanging) будет согласовано с внешней практикой и улучшит надёжность, при условии документирования изменений в ADR.

## 3. BatchSpanProcessor Overflow Semantics

### Does stock BSP support drop-oldest?

Исходный `BatchSpanProcessor` в Java SDK использует bounded очередь и при превышении `maxQueueSize` выбрасывает **новые** spans, фиксируя это через метрику и, возможно, лог. Аналогичное поведение описано и для Node.js SDK: при заполнении queue новые spans silently drop, и диагностический лог может писать `Dropping span because maxQueueSize reached`.[^5][^6][^4]

Таким образом, стандартный BSP реализует overflow‑политику drop‑new, а не drop‑oldest.

### Evidence

- GitHub `BatchSpanProcessor.java` и связанные issues по логированию drop span при maxQueueSize.[^4][^5]
- Статьи/руководства по tuning BatchSpanProcessor в разных языках, описывающие «новые спаны отбрасываются при переполнении очереди».[^6]

### Can it replace this custom processor?

Нет: BSP не может заменить `PlatformDropOldestExportSpanProcessor`, потому что:
- текущая платформа требует drop‑oldest semantics (evict oldest, accept newest) для поддержания недавних спанов при переполнении очереди — это зафиксировано в ADR и тестах.[^14][^13]
- BSP не предоставляет конфигурируемую overflow‑политику; поведение hard‑coded.

Чтобы использовать BSP как backend, пришлось бы добавить ещё один слой, реализующий drop‑oldest поверх drop‑new, что усложняет concurrency и добавляет двойной буфер.[^14]

### If not, why?

Причины невозможности прямой замены:
- Semantics: BSP выбрасывает новые spans, а платформа требует сохранения последних (newest). Это непосредственно влияет на качество трассировки при burst‑нагрузке.[^6][^14]
- Observability: текущий процессор ведёт дополнительные counters (`droppedSpansOverflow`, `droppedSpansAfterShutdown`, `exportFailures`, `exportTimeouts`) и JMX‑getters; BSP по умолчанию опирается на метрики, которые могут быть отключены или не проводиться через pipeline, и не имеет JMX‑контракта в платформе.[^21][^4][^10]
- Integration: `PlatformDropOldestExportSpanProcessor` включён через кастомный фабричный код, SPI‑customizer и JMX‑регистратор; замена на BSP нарушит этот wiring и rollback‑стратегию через env‑var.[^21][^12]

## 4. Java Concurrency Findings

### ReentrantLock + Condition.awaitNanos

**Что предоставляет.** `ReentrantLock` — явный lock с возможностью fairness и tryLock; `Condition` — отдельное условие для ожидания/сигналов; `awaitNanos(long)` освобождает lock, ждёт сигнал/прерывание/таймаут, затем вновь захватывает lock и возвращает оставшееся время; допускаются «spurious wakeups», поэтому вызов должен быть в цикле с проверкой условия.[^22]

**Помогает?** Да, модель, использованная в классе (единственный lock, Condition, `awaitNanos` для scheduleDelay/flush/shutdown) соответствует рекомендациям JDK и даёт детерминированный контроль над критической секцией и ожиданием.[^22][^19]

**Риск изменения поведения.** Нестандартное использование `awaitNanos` с явным unlock в catch и guard в finally, как сейчас, создаёт maintenance hazard: изменение структуры try/catch/finally может привести к double‑unlock или утечке lock.[^19][^22]

**Рекомендация.** Сохранять ReentrantLock+Condition паттерн, но максимально избегать ручного явного unlock вне finally; при рефакторинге queue/worker следует переработать interrupted‑path так, чтобы не требовалось ручное unlock, сохраняя неблокирующий `exporter.export()` вне lock.

### BlockingDeque / LinkedBlockingDeque

**Что предоставляет.** `BlockingDeque` — двусторонняя блокирующая очередь с методами `offerFirst/offerLast/pollFirst/pollLast`, которые могут блокировать, возвращать false или бросать исключения in зависимости от перегрузки.[^23]

**Помогает?** Может упростить собственную реализацию очереди (использовать `pollFirst`/`offerLast` для drop‑oldest), но по умолчанию `offerLast` без таймаута блокирует при полной очереди, что противоречит требованию неблокирующего `onEnd`.[^23][^12]

**Риск изменения поведения.** Замена ручной ArrayDeque+ReentrantLock на BlockingDeque без тщательного выбора неблокирующих методов приведёт к блокировке producer threads при высокой нагрузке; также изменится профиль задержек и scheduleDelay/flush‑поведение, так как blocking queue сама координирует waiting consumers.[^23]

**Рекомендация.** Использовать BlockingDeque лишь как возможную внутреннюю реализацию для extracted queue wrapper при строгом использовании неблокирующих операций (`offer` возвращает false, затем эвиктить старый элемент) и сохранении контроля над lock и Condition; прямой переход на стандартную блокирующую модель (V7) не рекомендуется.

### ArrayBlockingQueue

**Что предоставляет.** `ArrayBlockingQueue` — bounded FIFO очередь с блокирующими и неблокирующими операциями; поддерживает `drainTo` для быстрого дренажа элементов.[^24]

**Помогает?** Может заменить ArrayDeque, предоставляя готовые blocking‑примитивы, но также склонна к блокирующему `put`/`take` и имеет сложную semantics удаления внутренних элементов, что JDK описывает как «intrinsically slow and disruptive».[^24]

**Риск изменения поведения.** Использование `put`/`take` нарушит неблокирующий `onEnd`; `drainTo` под нагрузкой может быть сложно контролировать и не интегрируется прямо с существующими lock‑паттернами.[^24]

**Рекомендация.** Не использовать ArrayBlockingQueue как прямую замену, если не реализовать explicit неблокирующий drop‑oldest wrapper; общая рекомендация — оставить ручную очередь или использовать BlockingDeque с осторожностью.

### ExecutorService

**Что предоставляет.** ExecutorService — абстракция пула потоков для выполнения задач; можно использовать fixed‑pool, cached, scheduled, и т.д. На Java 21 его можно заменить на виртуальные потоки через `Executors.newVirtualThreadPerTaskExecutor()`.[^25][^26]

**Помогает?** Может обобщить worker‑thread модель, позволяя проще управлять жизненным циклом воркера и shutdown; однако текущий дизайн уже использует один daemon‑thread, и дополнительная сложность пула не даёт существенного выигрыша.[^19]

**Риск изменения поведения.** Перевод worker с dedicated daemon‑thread на ExecutorService может изменить свойства именования, shutdown и наблюдаемости; также сложнее обеспечить корректный shutdown при зависшем exporter.[^27]

**Рекомендация.** Сохранять текущую модель с одним dedicated thread; рассматривать ExecutorService только при переходе к более сложной многопоточной архитектуре, что выходило бы за рамки текущего refactoring.

### Virtual threads / structured concurrency

**Что предоставляет.** Virtual threads в Java 21 — лёгкие потоки, управляемые JVM, позволяющие создавать десятки тысяч concurrent задач; structured concurrency (`StructuredTaskScope`) обеспечивает более безопасное управление группами задач (отмена при fail, координация shutdown).[^26][^25][^27]

**Помогает?** Для I/O‑bound задач виртуальные потоки дают супер‑масштабируемость и упрощают async‑код; в текущем процессоре один worker и небольшой набор задач — выгода от virtual threads минимальна, но structured concurrency может быть полезна для комплексного shutdown/forceFlush orchestration, если архитектура станет существенно сложнее.[^28][^25]

**Риск изменения поведения.** Использование synchronized/lock с virtual threads может «pin» виртуальный поток на carrier‑thread, снижая выгоду; для такого низкоуровневого компонента, как SpanProcessor, введение Loom‑фич добавляет сложность без явной необходимости.[^29][^25]

**Рекомендация.** Не переводить данный класс на virtual threads/StructuredTaskScope в рамках текущего refactoring; сосредоточиться на исправлении существующей lock‑модели и таймаутов.

### Ring buffers / Disruptor

**Что предоставляет.** LMAX Disruptor — библиотека, предоставляющая высокопроизводительный кольцевой буфер (`RingBuffer`) с разными `ProducerType` и `WaitStrategy`, ориентированный на низкую latency и высокую throughput для событийных очередей; включает DSL для настройки event processors и координации.

**Помогает?** Для высоконагруженных событийных систем Disruptor обеспечивает лимитированную очередь и эффективную обработку; в принципе, можно реализовать bounded queue и drop‑policies через WaitStrategy/handlers.[^30][^31]

**Риск изменения поведения.** Интеграция Disruptor требует полной переработки модели producer/consumer, WaitStrategy и shutdown; overflow‑поведение и backpressure зависят от конфигурации и значительно отличаются от простых ArrayDeque+ReentrantLock; это тяжёлый dependency и существенно смещает архитектуру агента.[^30]

**Рекомендация.** Не использовать Disruptor в данной платформе; потребности по performance уже покрываются текущей архитектурой (подтвержденной JMH), а Disruptor приводил бы к over‑engineering.

## 5. Production Reliability Patterns

### Bounded shutdown

В различных SDK и обсуждениях отмечается, что shutdown процессов должен быть ограничен по времени: либо через таймаут параметра, либо через структуру `CompletableResultCode`/future, который завершается при timeout/fail. В C++/Ruby спеках `Shutdown(timeout)` возвращает результат и после этого дальнейшие вызовы возвращают успех без работы.[^20][^7][^8][^3]

Для данной платформы это означает, что shutdown‑логика `PlatformDropOldestExportSpanProcessor` должна ограничивать ожидание `exporter.shutdown()` и умеет завершать `shutdownResult` даже при зависании exporter.

### Exporter shutdown timeout

Issues по Java SDK показывают, что многие exporters при shutdown либо сразу возвращают success (например, Jaeger flush), либо «gracefully» завершают текущие задачи и отменяют новые; при этом нет жёсткой гарантии доставки всех спанов, и существуют баги, когда senders не гарантируют экспорт спанов во время shutdown. Это усиливает аргумент за bounded timeout вокруг exporter.shutdown и чёткое инкрементирование счётчиков потерь.[^16][^7]

### forceFlush completion guarantees

Практика других языков и exporters: `forceFlush` должен либо попытаться экспортировать незавершённые спаны, либо вернуть немедленный успех (особенно в FaaS‑средах), но не должен зависать. Таким образом, внутренняя координация `pendingFlushes` и worker в кастомном процессоре должна гарантировать завершение всех promises, либо отметку их как fail при shutdown.[^20][^3]

### Worker death handling

В production‑системах принято считать смерть ключевого worker’а критическим событием: необходимы либо механизмы перезапуска, либо явный fail‑fast с логами/алертами. В OTel Java SDK issues упоминаются проблемы с shutdown race и незаметным termination обработчиков. Для данного класса worker death от unchecked RuntimeException должен приводить к безопасному shutdown, корректно завершающему `shutdownResult`, и к явной сигнализации, а не к тихой остановке экспорта.[^32][^7]

### Logging throttling

Issues по BSP показывают, что при drop spans логирование по умолчанию отсутствует и пользователям сложно диагностировать потери; предлагается добавить WARN‑логи, но при этом важно не зафлудить лог при массовых потерях. Паттерн `logExportFailureOnce` (один WARN на первую failure) отражает эту идею throttling; при рефакторинге нужно сохранять подобную семантику, возможно расширяя её через метрики.[^4]

### Counters and metrics

Практика SDK — использовать метрики и счётчики для мониторинга drop spans, export failures, retries и т.д.; в Java BSP метрики используются для фиксации drop при maxQueueSize. Платформа добавила свои счётчики и JMX‑getters; refactoring должен сохранить их semantics и согласовать с внешними паттернами (например, явно документировать, что `exportTimeouts` ⊆ `exportFailures`).[^6][^4]

## 6. Applicability Matrix

| Variant / Technique                          | Preserves drop-oldest | Preserves non-blocking onEnd | Reduces concurrency risk | Complexity | Performance risk | Production suitability |
|---------------------------------------------|------------------------|------------------------------|--------------------------|-----------|------------------|------------------------|
| Current manual lock + ArrayDeque            | Да (при сохранении кода) | Да (lock только на O(1) ops) | Нет (нестандартный unlock‑паттерн) | Средняя | Низкий (JMH baseline) | Высокая (уже в проде) |
| Extracted queue wrapper (C1)                | Да (можно сохранить)   | Да (при сохранении неблокирующих методов) | Потенциально да, если убрать ручной unlock | Высокая | Потенциально низкий | Высокая при осторожном внедрении |
| BlockingDeque                               | Да (pollFirst+offerLast) | Риск (offer может блокировать) | Частично (стандартные примитивы) | Средняя–высокая | Средний (другой overhead) | Средняя, но требует осторожности |
| ExecutorService worker                      | Не влияет напрямую     | Да (если не блокировать submit) | Частично (упрощение lifecycle) | Средняя | Низкий–средний | Средняя, но не даёт заметной выгоды |
| Virtual threads / Structured concurrency     | Да                     | Да, но synchronized/locks могут pin'ить | Не напрямую, меняют модель | Высокая | Неопределённый | Низкая для данного компонента |
| Ring buffer / Disruptor-style architecture  | Да (bounded buffer)    | Да (при правильной конфигурации) | Да (patternized concurrency), но сложно | Очень высокая | Неопределённый, потенциально низкий | Низкая, over‑engineering |
| Stock BSP adapter (drop-new)                | Нет (drop-new)         | Да                             | Да (стандартная реализация) | Средняя | Низкий           | Низкая, нарушает требования платформы |

В этой матрице ясно, что текущая ручная модель lock+ArrayDeque остаётся самой надёжной с точки зрения известных invariants и performance, а любые серьёзные изменения (BlockingDeque, Disruptor) либо нарушают non‑blocking onEnd, либо усложняют архитектуру без явной производительной необходимости.[^30][^23][^6]

## 7. Final External Research Conclusions

1. Стандартный `BatchSpanProcessor` реализует drop‑new overflow и не может удовлетворить drop‑oldest требования платформы; замена кастомного процессора на BSP недопустима.[^5][^4][^6]
2. Контракты `SpanProcessor.forceFlush`/`shutdown` в разных языках требуют bounded времени и немедленного no‑op поведения после shutdown; текущая репозитория‑семантика (forceFlush after shutdown → ofSuccess) согласуется с этим и должна быть сохранена.[^2][^8][^3]
3. `SpanExporter` и `CompletableResultCode` предполагают, что таймауты экспортов должны явно сигнализировать failure; counters `exportTimeouts` как подмножество `exportFailures` — корректный и ожидаемый паттерн.[^18][^1]
4. Реализация bounded shutdown для exporter (таймаут вокруг `exporter.shutdown()` и корректное завершение `shutdownResult`) согласуется с практикой других exporters и решает известные проблемы с зависаниями senders.[^7][^16]
5. Использование ReentrantLock+Condition.awaitNanos остаётся подходящим низкоуровневым примитивом для очереди/воркера; refactoring должен минимизировать нестандартное ручное unlock‑поведение и опираться на стандартную схему `lock → awaitNanos → finally unlock`.[^22]
6. BlockingDeque/ArrayBlockingQueue не предоставляют «из коробки» неблокирующий drop‑oldest; их применение требует custom wrapper, иначе будет нарушен контракт неблокирующего `onEnd`.[^24][^23]
7. Virtual threads/structured concurrency не дают существенного выигрыша для single‑worker, но могут осложнить lock‑модель; их внедрение в данный класс нецелесообразно в рамках текущего refactoring.[^25][^26]
8. Disruptor/ring buffer архитектуры — мощный, но тяжёлый инструмент, ориентированный на high‑throughput event‑системы; для агент‑компонента с уже достаточной производительностью они представляют собой over‑engineering.[^31][^30]
9. Production‑grade telemetry processors обычно используют bounded shutdown, таймауты exporter, гарантированное завершение forceFlush, явную обработку death‑worker и лог‑throttling; эти паттерны следует перенести в кастомный процессор при сохранении его специфических invariants.[^33][^7][^4]
10. Refactoring‑план должен исходить из того, что архитектура lock+ArrayDeque отвечает требованиям платформы и может быть безопасно улучшена только через малые, хорошо протестированные шаги (извлечение config/exporter/observability) перед любыми попытками изменения очереди/воркера.

## 8. Source Appendix

-  OpenTelemetry tracing SDK spec — общие обязанности трейсинг‑SDK и SpanProcessor.[^9]
-  Старый Javadoc SpanProcessor(Java) — определение forceFlush/shutdown/onEnd контрактов.[^2]
-  OpenTelemetry Java SDK docs — описание shutdown/forceFlush для SDK.[^3]
-  Javadoc SpanExporter(Java) — экспорт/flush/shutdown контракты и поведение BatchSpanProcessor при экспортах.[^1]
-  GitHub issue CompletableresultCode.join timeout — поведение join при таймауте и семантика failure.[^18]
-  GitHub BatchSpanProcessor.java — реализация стандартного batch‑процессора, подтверждение bounded queue/drop‑new.[^5]
-  GitHub issue «BatchSpanProcessor drops span» — подтверждение drop‑new overflow с метриками, отсутствие логов по умолчанию.[^4]
-  Node.js BatchSpanProcessor tuning guide — описания overflow поведения и tuning параметров.[^6]
-  JDK Condition.awaitNanos docs — описание semantics ожидания, spurious wakeups и lock‑поведения.[^22]
-  JDK ArrayBlockingQueue docs — semantics bounded очереди и особенности drain/remove.[^24]
-  JDK BlockingDeque docs — semantics offer/poll и блокирующие операции на bounded deque.[^23]
-  Java 21 virtual threads/structured concurrency guide — overview virtual threads, StructuredTaskScope, влияние на блокирующие операции.[^25]
-  RedHat JDK 21 overview — новые фичи, включая virtual threads.[^26]
-  Beyond Loom article — новые concurrency‑паттерны и их влияние.[^27]
-  LMAX Disruptor user guide — описание ring buffer, производительность и дизайн.[^30]
-  LMAX Disruptor RingBuffer Javadoc — детали создания и использования ring buffer.[^31]
-  JaegerThriftSpanExporter docs — поведение shutdown/flush/export в конкретном exporter.[^16]
-  LoggingSpanExporter docs — подтверждение контрактов export/flush/shutdown.[^15]
-  AzureMonitorExporter docs — пример стороннего exporter с CompletableResultCode.[^17]
-  C++ SpanProcessor docs — мульти‑языковой контракт forceFlush/shutdown с таймаутом.[^8]
-  Ruby SpanProcessor docs — семантика force_flush/shutdown с timeout.[^20]
-  Article on SDK shutdown/forceFlush config — рекомендации по bounded shutdown/forceFlush.[^33]
-  Issue on SDK shutdown race — пример проблем с shutdown‑поведением в Java SDK.[^32]

---

## References

1. [SpanExporter (OpenTelemetry SDK For Tracing)](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/export/SpanExporter.html)

2. [Interface SpanProcessor](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk/0.7.1/io/opentelemetry/sdk/trace/SpanProcessor.html)

3. [Manage Telemetry with SDK - Java - OpenTelemetry](https://opentelemetry.io/docs/languages/java/sdk/) - The SDK is the built-in reference implementation of the API, processing and exporting telemetry prod...

4. [Alert through logs when BatchSpanProcessor drops span · Issue #7103 · open-telemetry/opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/issues/7103) - Is your feature request related to a problem? Please describe. A clear and concise description of wh...

5. [opentelemetry-java/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java at main · open-telemetry/opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java) - OpenTelemetry Java SDK. Contribute to open-telemetry/opentelemetry-java development by creating an a...

6. [How to Fix OpenTelemetry Node.js SDK Silently Dropping Spans ...](https://oneuptime.com/blog/post/2026-02-06-fix-nodejs-batch-processor-queue-full/view) - Detect and fix silent span dropping when the OpenTelemetry BatchSpanProcessor queue reaches its maxi...

7. [Senders do not guarantee delivery of spans being processed while shutdown · Issue #6160 · open-telemetry/opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/issues/6160) - Describe the bug All sender do not gracefully shutdown, instead they cancel everything in process. T...

8. [Class SpanProcessor](https://opentelemetry-cpp.readthedocs.io/en/latest/otel_docs/classopentelemetry_1_1sdk_1_1trace_1_1SpanProcessor.html)

9. [Tracing SDK](https://opentelemetry.io/docs/specs/otel/trace/sdk/) - Status: Stable, except where otherwise specified Tracer Provider Tracer Creation It SHOULD only be p...

10. [01-current-behavior.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/90c0583c-2d23-4918-a1bc-437a9f1b3c8b/01-current-behavior.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=CTJwQRWXxO2V3wQ81p1v47GS0sQ%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - Evidence-first. All line references are to platform-tracing-otel-extensionsrcmainjavaspacebr1440plat...

11. [05-test-coverage-and-gaps.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/88bd7607-aee4-4478-abdb-19edc33e25c1/05-test-coverage-and-gaps.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=D6OYkfqQ9xuG9aaMi43QGGRXhrU%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - # Test Coverage and Gaps: PlatformDropOldestExportSpanProcessor

> Existing tests are described ba...

12. [06-refactoring-constraints.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/ccba0a44-ad7d-4d4f-8c04-43aacd8519b5/06-refactoring-constraints.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=BSzuKVBVT9dMCfZCkAHa7EZHtCY%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - # Refactoring Constraints: PlatformDropOldestExportSpanProcessor

> Hard constraints must not be v...

13. [00-executive-summary.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/97610b35-3a89-4f42-ad44-649f2c518afa/00-executive-summary.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=JZCZCC47KPq92TropQLWUO8M0hA%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - Field Value -------------- Target class space.br1440.platform.tracing.otel.extension.processor.Platf...

14. [07-llm-research-input.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/7ac3fde2-9f38-48b4-ac8d-96bcf4ded21b/07-llm-research-input.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=p5LsbXg2xG3Nqpqh%2BzUzATMxbwY%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - This file is optimized as input for Perplexity Deep Research or external LLM architectural scoring. ...

15. [Class LoggingSpanExporter](https://javadoc.io/static/io.opentelemetry/opentelemetry-exporters-logging/0.9.1/io/opentelemetry/exporters/logging/LoggingSpanExporter.html)

16. [Class JaegerThriftSpanExporter](https://javadoc.io/static/io.opentelemetry/opentelemetry-exporter-jaeger-thrift/1.10.0-rc.2/io/opentelemetry/exporter/jaeger/thrift/JaegerThriftSpanExporter.html) - declaration: package: io.opentelemetry.exporter.jaeger.thrift, class: JaegerThriftSpanExporter

17. [AzureMonitorExporter Class](https://learn.microsoft.com/en-us/java/api/com.azure.opentelemetry.exporters.azuremonitor.azuremonitorexporter?view=azure-java-preview) - This class is an implementation of OpenTelemetry SpanExporter that allows different tracing services...

18. [CompletableResultCode.join() suprisingly fail()s on timeout. #2942](https://github.com/open-telemetry/opentelemetry-java/issues/2942) - It also reduces the flexibility of the method as calling fail() in the catch(TimeoutException) is ea...

19. [02-concurrency-model.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/93c8563e-f45c-41fd-8e96-abf320205f57/02-concurrency-model.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=CWQxpeNnY%2BOPfo8O0RuLvrgXusU%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - Evidence-first. All line references are to PlatformDropOldestExportSpanProcessor.java. --- TITLE Con...

20. [Class: OpenTelemetry::SDK::Trace::SpanProcessor](https://open-telemetry.github.io/opentelemetry-ruby/opentelemetry-sdk/v1.1.0/OpenTelemetry/SDK/Trace/SpanProcessor.html)

21. [03-adjacent-code-map.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/794159852/b9c2a0ea-1916-4123-a8a6-7e9a3460e3c7/03-adjacent-code-map.md?AWSAccessKeyId=ASIA2F3EMEYE5O2BZRIA&Signature=MlfKzlYJyIfytU2gfoiNSDHnrLk%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEPv%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJIMEYCIQDhzXtwIYYLBzEWmQjnwh%2BxAwwUfqEbw5FgGWWjBz4q4gIhAL%2FMJ%2BxQfr%2FfFo6Tj0nuLu6ZDHfB%2F2IY9k7viypPigEwKvwECMT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEQARoMNjk5NzUzMzA5NzA1IgwgELkzXbQqgQ%2BmOAgq0ASP0VN72FEp3QEQbDpYFLSlwmt6hhgFNz%2FWWSupALUBsMMTATH6pAuZKltML8f6mXrJg54p7yF2X73BoxeOGL1C%2F%2FxBoNxGvRRQx9ehpeiZ%2FKp5MVsRL5HezQyeQpYIKg512gTrVadmn%2BMm8T7IWDtWrZaeEgMB4j00ZbSsfvOP%2BoQzYyzwSO7Y6JITDzv0Zm0Y4OERtmevYkwaW4GkmKw1z1gCsELPJ%2B%2B05RkdrlkKLmBgygRbLi8ERc1Zc5i1OzhlzX66BZKuxrylWT5qmwP1GUiE43b%2F8LVhgxOjHzs4%2FedqBLmDpArdi%2Ftq2F1oG81rFikNBra6FjaI0MJO6NifOZajeNRJTrB2M0fRldQtoH2S1DSd4DeLbcIUPKQ%2BtJ3UYFNhOLXEYKDItVF7VtaeYFmblUR6nRSSsv8gv229vfsJqMNPuWJo47iKRynP8DrvEcjs0VpHlPB59Vve9s75kVWQT359rmOmBepIKQzURkwxpkFjox4TpfoK8nSIeWurRH35PpHSKoQumNQtPistS7NZob5V5DEgcCLnVY3pYo7DU9xd4cq2y8AZHk3Ueem5moacCWGJiMnUpjLnQGU%2FZ6iyoQS2uAUD4G8lsnunhX1Nz49p%2F0xQcCJe13fb5RfFO%2BI6WT53QLWCvyVOWkftHqdbe52QrG4mQQ6bkyx00c%2FC9%2BAq4qO8iBhMAhjpn78IrJLmbqPBFBaFUyHJQhlyuKzHNY0IRqCZ%2FYYBTkC9no0TQ1WqrEN9C%2FycMnbo8JEg22FGSRKSU92BfCqppyf4MPq2jtIGOpcB4QfA4h7q6eUI2MQIN3f0pz2Rh2DGh50p8Wb2APjij9VlwqJ9CFXcmVVxRx8MZBkcebC3jvNKVqtjC%2FqrcOccWhp4hkrItl%2F0vJFjkDor9zMGTHxXFyyeoUeOOvTwv43WxoDyiwt9VjVfmEXWBnO9MNviD3wkV%2BYT%2FK2gTSHVEqbykYnFM28qY5Bw4IIduZylvfw04MKCoA%3D%3D&Expires=1782819149) - Every entry is evidence-based. Speculative entries are labeled SPECULATION. --- TITLE Adjacent Code ...

22. [Condition (Java Platform SE 8 ) - Oracle Help Center](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Condition.html) - java.util.concurrent.locks. Interface Condition. All Known Implementing ... When waiting upon a Cond...

23. [Java Platform, Standard Edition Java API Reference](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingDeque.html) - declaration: module: java.base, package: java.util.concurrent, interface: BlockingDeque

24. [ArrayBlockingQueue (Java Platform SE 8 )](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ArrayBlockingQueue.html)

25. [Java 21 Virtual Threads and Structured Concurrency: Complete ...](https://java.elitedev.in/java/java-21-virtual-threads-and-structured-concurrency-complete-performance-guide-with-spring-boot-inte-5245cb93/) - Master Java 21's virtual threads and structured concurrency. Learn implementation, performance optim...

26. [What's new for developers in JDK 21](https://developers.redhat.com/articles/2023/09/21/whats-new-developers-jdk-21) - Java 21 is here! Explore new features in JDK 21, including virtual threads, record patterns, and seq...

27. [Beyond Loom: Weaving new concurrency patterns](https://developers.redhat.com/articles/2023/10/03/beyond-loom-weaving-new-concurrency-patterns) - Explore new concurrency patterns enabled by virtual threads in Java 21, along with two related featu...

28. [Structured Concurrency in Java 21 : Why It Matters and How It Works](https://www.youtube.com/watch?v=nR2NsVF6uhc) - 🚀 Structured Concurrency in Java: Why It Matters & How to Use It 🚀

Traditional concurrency (a.k.a. ...

29. [Virtual Threads and Structured Concurrency | Java 21 Features](https://www.youtube.com/watch?v=vxyQh5gr9us) - In this video, we delve into the latest features introduced in Java 21, demonstrating them with prac...

30. [LMAX Disruptor User Guide](https://lmax-exchange.github.io/disruptor/user-guide/index.html) - The Disruptor is a library that provides a concurrent ring buffer data structure. It is designed to ...

31. [Uses of Class com.lmax.disruptor.RingBuffer](https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/class-use/RingBuffer.html)

32. [Race condition in SDK shutdown · Issue #6827 · open-telemetry/opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/issues/6827) - Describe the bug In some circumstances, after successful execution of an instrumented program, an ex...

33. [Configure OpenTelemetry SDK shutdown timeout and ForceFlush in ...](https://oneuptime.com/blog/post/2026-02-06-otel-sdk-shutdown-java-spring-boot/view) - Configure OpenTelemetry SDK shutdown timeout and ForceFlush in Java Spring Boot applications to prev...

