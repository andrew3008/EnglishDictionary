<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are Gemini Pro inside Perplexity, acting as a broad-context architecture researcher.

Role:
Act as a senior distributed systems and observability architect. Your strength is broad comparison across design alternatives and identifying overlooked architectural options.

Task:
Analyze `PlatformDropOldestExportSpanProcessor` and produce a broad alternative architecture survey.

Context:
This is an enterprise Java/OpenTelemetry tracing component. It must preserve drop-oldest semantics, non-blocking `onEnd`, OpenTelemetry contracts, JMX getters, Builder API, and opt-in rollback behavior.

Attached files:
Use all attached repository evidence and current behavior/concurrency/refactoring docs.

Your specific goal:
Do not only evaluate the 8 known variants. Also look for design alternatives that the team may have missed.

Mandatory variants:
V1: targeted fixes
V2: TimedExporter extraction
V3: config extraction
V4: queue extraction
V5: low-risk bundle
V6: full decomposition
V7: Java concurrency primitives
V8: ring buffer / Disruptor

Additional requirement:
Identify at least 5 possible "V9-like" ideas, but do not recommend them unless they clearly satisfy constraints. Examples:

- using OpenTelemetry Collector for overflow policy instead of SDK processor
- wrapping exporter differently
- bounded shutdown adapter
- `SpanExporter` decorator chain
- metrics-only hardening
- executor-based worker
- state-machine-based lifecycle
- queue ownership inversion
- test harness first architecture

For each extra idea:

- description
- why it might help
- why it might fail
- whether it should be researched further

Output in Russian:

## 1. Executive Summary

- What the standard 8 variants miss
- Which alternatives are worth considering
- Which alternatives are distractions


## 2. Current Architecture Pressure Points

Summarize:

- responsibility overload
- concurrency hazards
- lifecycle hazards
- observability coupling
- OpenTelemetry coupling


## 3. Comparative Architecture Survey

Create a table comparing:

- monolithic hardening
- extracted components
- queue abstraction
- worker abstraction
- exporter decorator chain
- lifecycle state machine
- ExecutorService worker
- BlockingDeque
- ring buffer
- collector-side policy
- other alternatives you find

Columns:

- preserves drop-oldest
- preserves non-blocking producer
- reduces double-unlock hazard
- simplifies forceFlush
- simplifies shutdown
- performance risk
- implementation risk
- dependency impact
- recommendation


## 4. Deep Dive on Overlooked Alternatives

At least 5 alternatives.

## 5. Architecture Patterns Applicable Here

Discuss patterns:

- facade + internal components
- state machine
- decorator
- bounded queue abstraction
- test harness as architecture boundary
- adapter around exporter
- lifecycle coordinator


## 6. Recommendation

State:

- best conservative architecture
- best long-term architecture
- alternatives to reject
- alternatives to investigate only after Phase 0

Be broad but practical. Do not recommend a new dependency unless the benefit is clearly proven.

Как Senior Distributed Systems \& Observability Architect, я проанализировал предложенные варианты рефакторинга `PlatformDropOldestExportSpanProcessor`.

Большинство из 8 стандартных вариантов сфокусированы на **внутренней структурной декомпозиции** (нарезке монолита на классы) или **смене базовых примитивов** (очереди). Однако в распределённых системах самые элегантные решения часто лежат в плоскости **перераспределения ответственности через паттерны** (например, декораторы) и **строгого управления состояниями** (state machines), а не простого извлечения компонентов.

Ниже представлен расширенный архитектурный обзор, включающий как классические варианты, так и упущенные альтернативы.

***

## 1. Executive Summary

- **Что упускают стандартные 8 вариантов:** Они фокусируются на внутреннем устройстве очереди и воркера, но игнорируют возможность *делегирования* (например, вынесение таймаутов и изоляции ошибок в паттерн Decorator вокруг `SpanExporter`) и *формализации жизненного цикла* (замену гонок флагов `AtomicBoolean` на строгую State Machine).
- **Какие альтернативы стоит рассмотреть:** Обертывание экспортера в цепочку декораторов (Decorator Chain) для решения проблемы зависания `exporter.shutdown()` (R1) и таймаутов экспорта вне логики процессора. Также стоит рассмотреть явную State Machine для координации гонки `forceFlush` и `shutdown` (R3).
- **Какие альтернативы отвлекают внимание:** Перенос политики drop-oldest на сторону OTel Collector (нарушает JMX-контракты агента и не спасает память приложения при сетевых сбоях), использование LMAX Disruptor (over-engineering), и прямая замена на `BlockingDeque` (риск блокировки `onEnd`).


## 2. Current Architecture Pressure Points

Текущая архитектура страдает от классических проблем монолитного дизайна инфраструктурных компонентов:

- **Responsibility overload:** Класс совмещает 9 зон ответственности (от валидации конфигурации до управления потоками и JMX-адаптеров).
- **Concurrency hazards:** Паттерн `queueLock.unlock()` внутри блока `catch (InterruptedException)` (риск R2) — это бомба замедленного действия. Разделение worker'а и очереди без изменения этого паттерна приведёт к cross-object lock leaks.
- **Lifecycle hazards:** `exporter.shutdown()` вызывается без таймаута (R1), смерть воркера от unchecked exception никак не обрабатывается (R4), а `forceFlush` промисы могут потеряться, если shutdown вызван параллельно (R3).
- **Observability coupling:** JMX-геттеры напрямую привязаны к внутренним `AtomicLong`. Очистка или изменение поведения `logExportFailureOnce` (R5) затронет логику экспорта.
- **OpenTelemetry coupling:** Процессор глубоко завязан на семантику `CompletableResultCode` и SPI-конфигурацию фабрики.


## 3. Comparative Architecture Survey

В таблице сравнены как изначальные V1–V8, так и новые архитектурные концепции (обозначены как *New*).


| Архитектурный подход | Preserves drop-oldest | Preserves non-blocking producer | Reduces double-unlock hazard | Simplifies forceFlush | Simplifies shutdown | Perf risk | Impl risk | Dep impact | Recommendation |
| :-- | :--: | :--: | :--: | :--: | :--: | :--: | :--: | :--: | :-- |
| **V1: Monolithic hardening** | Да | Да | Нет | Частично | Да | Низкий | Низкий | Нет | **GO (near-term)** |
| **V3/V5: Extracted components** | Да | Да | Нет | Нет | Нет | Низкий | Средний | Нет | **GO (phased)** |
| **V4: Queue abstraction** | Да | Да | **Да*** | Нет | Нет | Средний | Высокий | Нет | **CAUTION** |
| **V6: Full decomposition** | Да | Да | Да | Да | Да | Средний | Высокий | Нет | **CAUTION (target)** |
| **V7: BlockingDeque** | Риск | **Нет** | Да | Нет | Нет | Высокий | Высокий | Нет | **NO GO** |
| **V8: Ring buffer (Disruptor)** | Да | Да | Да | Нет | Нет | Неизв. | Высокий | Да | **NO GO** |
| **New 1: Exporter Decorator Chain** | Да | Да | Нет | Нет | **Да** | Низкий | Низкий | Нет | **GO (for C3/C5)** |
| **New 2: Lifecycle State Machine** | Да | Да | Нет | **Да** | **Да** | Низкий | Средний | Нет | **GO (target)** |
| **New 3: ExecutorService worker** | Да | Да | Да | Риск | Да | Средний | Высокий | Нет | **CAUTION** |
| **New 4: Collector-side policy** | Нет | Да | Да | Да | Да | Низкий | Низкий | Нет | **NO GO** |

*\*Только при правильном совместном проектировании (co-design) интерфейсов блокировок.*

## 4. Deep Dive on Overlooked Alternatives

Помимо предложенных V1-V8, я выделил 5 альтернативных (или дополняющих) подходов "V9-типа".

### A1. SpanExporter Decorator Chain (Bounded Shutdown \& Timed Adapter)

**Описание:** Вместо того чтобы реализовывать таймауты `exportBatch` и пытаться ограничить зависающий `exporter.shutdown()` внутри логики воркера, мы оборачиваем оригинальный `SpanExporter` в `TimedSpanExporter` и `BoundedShutdownSpanExporter`.

- **Почему это поможет:** Позволяет убрать логику таймаутов, инкремента счетчиков ошибок (exportFailures) и зависаний (R1) из `PlatformDropOldestExportSpanProcessor` вообще. Процессор будет просто вызывать `.export()` и `.shutdown()`, делегируя контроль времени декораторам. Это чистая реализация варианта V2 (TimedExporter) через стандартный паттерн OTel.
- **Почему может провалиться:** Сложность с маршрутизацией инкремента метрик (`getExportFailures`), так как декоратор должен иметь доступ к `AtomicLong` основного процессора или предоставлять свои метрики для агрегации JMX.
- **Вердикт:** **ИССЛЕДОВАТЬ.** Это самый безопасный способ устранить риск R1 (exporter hang).


### A2. Explicit State Machine-based Lifecycle Coordinator

**Описание:** Замена разрозненных флагов `shutdownRequested`, `workerTerminated` и `shuttingDown` на строгий Thread-Safe автомат состояний (например, `AtomicReference<State>`), где `State` = `RUNNING` -> `FLUSHING_SHUTDOWN` -> `DRAINING` -> `TERMINATED`.

- **Почему это поможет:** Полностью решает гонку R3 (forceFlush promise lost). Если вызов `forceFlush` происходит в состоянии `DRAINING`, конечный автомат может детерминированно вернуть `ofSuccess()` или немедленно присоединить промис к текущему drain-циклу.
- **Почему может провалиться:** Over-engineering, если реализовано через тяжелые внешние библиотеки. Должно быть реализовано на простых `AtomicReference`.
- **Вердикт:** **ИССЛЕДОВАТЬ.** Жизненно необходимо при реализации V6 (Full decomposition) для управления компонентами.


### A3. Single-Threaded Executor Worker (Instead of Manual Thread)

**Описание:** Замена `Thread workerThread` и цикла `while (!shuttingDown)` на `Executors.newSingleThreadExecutor()` и submit задач.

- **Почему это поможет:** Решает риск R4 (Worker death from unchecked exception). `ExecutorService` может быть настроен на перезапуск воркера или корректный fail-fast. Убирается "грязный" паттерн обработки `InterruptedException` с `isHeldByCurrentThread()`.
- **Почему может провалиться:** Сложно реализовать паттерн `scheduleDelay` без `Condition.awaitNanos()`. Придется использовать `ScheduledExecutorService`, что меняет профиль производительности и логику пакетного drain'а (нужно избегать гонок между задачами flush и timer).
- **Вердикт:** **ОТЛОЖИТЬ.** Риск изменения producer-consumer семантики слишком высок для ранних PR.


### A4. Collector-Side Overflow Policy (Offloading the Problem)

**Описание:** Удаление кастомного процессора из Java агента. Использование дефолтного `BatchSpanProcessor` с огромной очередью, а политику drop-oldest реализовывать на стороне OpenTelemetry Collector.

- **Почему это поможет:** Снимает всю кодовую базу (575 строк) с поддержки. Уменьшает overhead агента.
- **Почему может провалиться:** Прямо **нарушает жесткие ограничения** проекта: JMX-счетчики потерянных спанов исчезнут из JVM. Если сеть между приложением и коллектором отпадет, приложение исчерпает память (OOM) до того, как спаны дойдут до коллектора, так как BSP не имеет drop-oldest.
- **Вердикт:** **ОТКЛОНИТЬ.** Нарушает production constraints.


### A5. Test-Harness First (Shadow Mode Architecture)

**Описание:** Прежде чем делать структурный рефакторинг V4/V6, мы создаем фасад, который запускает одновременно текущий монолитный `PlatformDropOldest...` и новый рефакторенный компонент, дублируя им `onEnd` (но отправляя экспорт нового только в NullExporter), и сравнивает JMX счетчики в реальном времени в тестовой среде.

- **Почему это поможет:** Идеальная гарантия behavior preservation (свойство 1). Позволяет убедиться, что lock contention не деградировал (Performance neutrality).
- **Почему может провалиться:** Требует написания сложной инфраструктуры сравнения. Двойное потребление памяти на очередь в тестах.
- **Вердикт:** **ИССЛЕДОВАТЬ.** Отличный подход для PR-0 / PR-1 на этапе интеграционного тестирования.


## 5. Architecture Patterns Applicable Here

При анализе класса вырисовывается необходимость применения классических системных паттернов, а не просто "Extract Class":

1. **Decorator (для Exporter'а):** Идеален для изоляции I/O проблем. Отделяет бизнес-логику очереди от факта, что внешняя система (backend) может "тормозить" при shutdown.
2. **Facade (для Builder и JMX):** Сохранение `PlatformDropOldestExportSpanProcessor` исключительно как публичной оболочки, которая скрывает за собой новую компонентную базу (отвечает constraint'ам).
3. **Bounded Queue Abstraction с External Lifecycle:** Очередь не должна знать о `shutdownRequested`. Ее методы должны быть: `enqueue()`, `pollBatch()`, `isFull()`. Точка. Решение, *что делать*, если она полна, должен принимать Coordinator. Это инверсия контроля, которая устранит double-unlock hazard.
4. **Lifecycle Coordinator:** Выделенный объект, который слушает сигналы от JVM (`shutdown`), от SDK (`forceFlush`), и переключает состояние воркера.

## 6. Recommendation

**Лучшая консервативная архитектура (Near-term):**

- **V3 (Config Extraction)** + **A1 (Exporter Decorator Chain)** + **V1 (Targeted fixes)**.
- Оставьте очередь и worker loop в основном классе. Оберните `SpanExporter`, переданный через Builder, во внутренний `BoundedShutdownExporter` (таймауты shutdown) и `TimedExporter` (сбор ошибок). Это решит R1, R5 и упростит монолит на 150+ строк вообще без риска изменения concurrency инвариантов (R2 останется, но будет локализован).

**Лучшая долгосрочная архитектура (Target Architecture):**

- **V6 (Full decomposition)**, но архитектурно управляемый **A2 (Lifecycle State Machine)**.
- Очередь (C1) становится глупой (`BoundedDropOldestQueue`). Воркер (C2) просто слушает State Machine. State Machine координирует `forceFlush` (C4) и `shutdown` (C5). Фасад держит JMX и Builder.

**Альтернативы к отклонению (NO GO):**

- **A4 (Collector-side policy)** — нарушает observability \& memory constraints.
- **V7 (BlockingDeque)** — риск блокировки producer'а.
- **V8 (Disruptor)** — необоснованный over-engineering.
- **A3 (ExecutorService worker)** — перепишет всю логику `scheduleDelay` без гарантий успеха.

**Альтернативы для исследования после Phase 0 (Characterization Tests):**

1. Внедрить A1 (Exporter Decorator) как способ элегантного извлечения `TimedExporter` (V2) и фикса зависаний (R1).
2. Реализовать A5 (Test-Harness Shadowing) при переходе к V4 (извлечение очереди), чтобы гарантировать отсутствие изменений в throughput и atomicity.
