<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are an Anthropic Claude reasoning model inside Perplexity.

Role:
Act as a conservative principal engineer reviewing a production-critical Java/OpenTelemetry refactoring proposal.

Your priority order:

1. Preserve behavior.
2. Preserve shutdown and forceFlush correctness.
3. Preserve non-blocking producer path.
4. Preserve observability compatibility.
5. Improve maintainability only where safety is high.

Context:
`PlatformDropOldestExportSpanProcessor` is a 575-line custom OpenTelemetry `SpanProcessor` for drop-oldest queue overflow semantics. The class currently mixes queue management, worker loop, export timeout, forceFlush, shutdown, builder/config validation, observability counters, and lifecycle state.

Attached files:

- source class
- current behavior dossier
- concurrency model
- adjacent code map
- responsibility decomposition
- test coverage and gaps
- refactoring constraints
- LLM research input

Task:
Produce a conservative architecture review and recommended safe refactoring path.

Important:
Do not optimize for elegance. Optimize for production safety.

Analyze these variants:
V1: Status quo + targeted production fixes
V2: Extract `TimedExporter`
V3: Extract `ProcessorConfig` / Builder facade
V4: Extract `BoundedDropOldestQueue`
V5: Extract low-risk components only: config + timed exporter + counters/state
V6: Full decomposition
V7: Replace queue internals with Java concurrency primitives
V8: Ring buffer / Disruptor-style architecture

For each variant:

- What behavior could break?
- What concurrency invariant could break?
- What tests must exist before implementation?
- Is the variant safe as a first PR?
- Is the variant safe as a later PR?
- What is the smallest reversible implementation slice?

Special focus:

- `forceFlush + shutdown` race
- `exporter.shutdown()` without timeout
- explicit early unlock in worker interrupt path
- worker death from unchecked exception
- `logExportFailureOnce` permanent throttle
- JMX getter compatibility
- Builder API compatibility
- OpenTelemetry SDK shutdown behavior

Output in Russian:

## 1. Principal Engineer Verdict

- Can refactoring start now?
- Which variant should be first?
- Which variants must be postponed?
- Which variants should be rejected?


## 2. Invariant Map

Create a table:

- invariant
- current implementation mechanism
- test coverage
- refactoring risk
- required characterization test


## 3. Variant-by-Variant Conservative Review

For V1–V8:

- verdict: GO / CAUTION / NO GO
- production risk
- behavior risk
- concurrency risk
- test prerequisites
- implementation slice
- rollback path


## 4. Recommended PR Sequence

Provide exact PR sequence:

- PR-0 characterization tests
- PR-1 lowest-risk extraction
- PR-2 targeted hardening
- PR-3 queue/worker decomposition only if justified
- PR-4 cleanup and ADR update

For each PR:

- scope
- files likely changed
- tests
- merge criteria
- rollback criteria


## 5. What Not To Do

List dangerous refactoring moves:

- examples: changing queue primitive too early, extracting worker before tests, adding Disruptor dependency, changing forceFlush semantics, changing JMX getters, etc.


## 6. Final Recommendation

Give a short decision memo for architects.

Be strict. If a variant is attractive but unsafe, say so clearly.

Ниже — полный отчёт консервативного Principal Engineer review.

***

## 1. Principal Engineer Verdict

**Рефакторинг прямо сейчас — нельзя.** Шесть из девяти критических concurrency-тестов отсутствуют (M1–M6). Без них любое изменение — слепое редактирование production-кода с известными, но неизолированными рисками.[^1][^2]

**Первый структурный PR после PR-0:** V3 (ProcessorConfig / Builder facade) — единственный вариант с нулевым concurrency-риском. Следом — V2 (TimedExporter).[^3][^4]

**Отложить:** V4 (BoundedDropOldestQueue) — только после M1/M2/M7 тестов и V2+V3 в проде; V6 (full decomposition) — после V2+V3+V4, не единым шагом.[^5][^6]

**Отклонить:** V7 (BlockingDeque/j.u.c.) и V8 (Disruptor) — причины в разделе 3.[^4][^5]

***

## 2. Invariant Map

| Инвариант | Механизм | Тест-покрытие | Risk | Требуемый тест |
| :-- | :-- | :-- | :-- | :-- |
| Drop-oldest: `pollFirst → offerLast` под lock | `enqueueWithDropOldest` lines 205–227 | Покрыт: SP-05T1–T5 | ВЫСОКИЙ при изменении lock | M1 (concurrent flush+shutdown) |
| `onEnd` не блокирует producer | `exporter.export()` всегда вне `queueLock` | Покрыт: SP-05T2 | ВЫСОКИЙ при изменении lock-границ | — (уже покрыт, re-verify при V4) |
| `droppedSpansOverflow + exported == TOTAL` | AtomicLong атомарен с eviction под lock | Покрыт | СРЕДНИЙ при V4 | — |
| `shutdown()` идемпотентен | `AtomicBoolean.compareAndSet`, кеш `shutdownResult` | Покрыт | НИЗКИЙ | — |
| `forceFlush()` после shutdown → `ofSuccess` | Fast-path check строки 113–114 | Частично (M9 отсутствует) | СРЕДНИЙ | M9 |
| `exportTimeouts ⊆ exportFailures` | Оба counter в `exportBatch` при timeout | Покрыт: lifecycle test | НИЗКИЙ при V2 | Smoke-тест при extraction C3 |
| Шесть JMX-геттеров: имена, типы, семантика | Public методы, `AtomicLong.get()` | Частично (нет source-compat теста) | СРЕДНИЙ при любом refactoring | Reflection-based compile-time assertion |
| `exporter.shutdown()` завершается за bounded time | **НЕТ ТАЙМАУТА (R1)** | **НЕ ПОКРЫТ** (M4 отсутствует) | **КРИТИЧЕСКИЙ** | M4: `NeverCompleteShutdownExporter` |
| `forceFlush` promise всегда завершается | `pendingFlushes` дренируется до exit worker | **НЕ ПОКРЫТ** (race R3) | ВЫСОКИЙ | M1, M3 |
| Worker death → `workerTerminated.countDown()` | `finally` в `workerLoop` | **НЕ ПОКРЫТ** (R4) | ВЫСОКИЙ | M2 |
| Interrupt path → no double-unlock | `isHeldByCurrentThread()` guard в finally | Частично (через shutdown timeout) | ВЫСОКИЙ при V4/V6 | M7 |


***

## 3. Variant-by-Variant Conservative Review

### V1 — Status quo + targeted fixes

**Verdict: GO** *(параллельно с PR-0)*

- **Production risk:** Низкий — только добавляем код, не реструктурируем
- **Behavior risk:** Минимальный — bounded timeout вокруг `exporter.shutdown()` меняет поведение только при зависании
- **Concurrency risk:** Низкий — lock-модель и interrupt path не трогаются
- **Test prerequisites:** M4 должен существовать **до** добавления таймаута, чтобы подтвердить fix
- **Implementation slice:** Добавить bounded join вокруг `exporter.shutdown()` в terminator thread; добавить явное `shutdownResult.fail()` при timeout; обеспечить drain forceFlush promises при R3-сценарии[^7][^5]
- **Rollback:** `git revert` одного commit; поведение возвращается к текущей hanging semantics

**Safe as first PR:** Да — только fixes R1 и R3, ничего другого.

***

### V2 — Extract TimedExporter

**Verdict: GO** *(после PR-0 и PR-1)*

- **Production risk:** Низкий — нет изменений lock-логики
- **Behavior risk:** Средний — `logExportFailureOnce` и оба counter **должны переезжать вместе**; если `exportFailureLogged` остаётся в основном классе — throttle дублируется[^5][^3]
- **Concurrency risk:** Низкий — `exportBatch()` уже вызывается вне `queueLock`
- **Test prerequisites:** M6 (logExportFailureOnce throttle) должен быть написан и GREEN до start V2[^1]
- **Implementation slice:** Создать package-private `TimedExporter` с `export(batch)→boolean`, `getExportFailures()`, `getExportTimeouts()`; JMX getters основного класса делегируют
- **Rollback:** Удалить `TimedExporter`, вернуть методы — один file change

**Safe as first PR:** Нет. **Safe as second structural PR:** Да.

***

### V3 — Extract ProcessorConfig / Builder facade

**Verdict: GO** *(первый структурный PR)*

- **Production risk:** Минимальный — нулевой runtime concurrency impact
- **Behavior risk:** Минимальный — validation semantics (WARN/fallback) должна переехать идентично
- **Concurrency risk:** Нулевой — Builder/config полностью вне lock-модели[^4]
- **Test prerequisites:** Все 5 builder tests GREEN без изменений; желателен reflection source-compat тест
- **Implementation slice:** Immutable record `ProcessorConfig`; `Builder.build()` создаёт record; `Builder` остаётся как `public static final class` в основном классе[^3]
- **Rollback:** Удалить `ProcessorConfig`, разложить поля обратно — ноль поведенческих изменений

**Safe as first PR:** Да — самый безопасный первый структурный шаг.

***

### V4 — Extract BoundedDropOldestQueue

**Verdict: CAUTION** *(только после M1, M2, M7 тестов и V2+V3 в проде)*

- **Production risk:** Высокий — перемещение lock ownership
- **Behavior risk:** Высокий — `droppedSpansOverflow` должен инкрементироваться **атомарно с eviction в той же lock-секции**; если C1 не получает counter/callback — atomicity нарушится[^5][^3]
- **Concurrency risk:** КРИТИЧЕСКИЙ — interrupt path maintenance hazard (R2) живёт в `workerLoop`, который использует `queueLock` напрямую; extraction C1 без co-design с C2 переносит hazard через class boundary[^5]
- **Test prerequisites:** M1, M2, M7 — строго обязательны. JMH baseline — обязателен
- **Critical:** C1 и C2 должны быть **co-designed в одном PR**, не по отдельности
- **Rollback:** Сложнее — требует тщательного восстановления lock-структуры

***

### V5 — Low-risk bundle (config + timed exporter + counters)

**Verdict: CAUTION** *(хорошая концепция, опасный как единый PR)*

- Сумма рисков V2+V3 не равна нулю при объединении[^6]
- При совместном извлечении counter ownership легче допустить ошибку
- **Рекомендация:** Реализовывать как последовательность V3 → V2, не объединять в один PR

***

### V6 — Full internal decomposition

**Verdict: CAUTION** *(правильная конечная цель, преждевременна как единый шаг)*

- **Concurrency risk:** КРИТИЧЕСКИЙ как единый шаг — C2/C5 имеют трёхстороннюю зависимость[^3]
- Если C5 (`ShutdownCoordinator`) и C2 (`ExportWorker`) не разделяют корректно `workerTerminated`, latch не decremented → `shutdownResult` никогда не завершается
- **Verdict как единый PR:** NO GO. После V3→V2→V4: CAUTION.

***

### V7 — Replace queue internals with Java concurrency primitives

**Verdict: NO GO**

- `LinkedBlockingDeque.offerLast()` по умолчанию **блокирует** при capacity limit → прямое нарушение invariant "onEnd не блокирует producer"[^8][^4]
- Для drop-oldest нужен pattern `if (!deque.offerLast(span)) { deque.pollFirst(); deque.offerLast(span); }` — но это **не атомарно** без внешнего lock, что требует ровно такого же `ReentrantLock`[^5]
- V7 не устраняет R2 (double-unlock hazard) — только переносит его в другую форму[^5]

***

### V8 — Ring buffer / Disruptor-style

**Verdict: NO GO**

- Drop-oldest в Disruptor не встроен — требует custom `EventHandler` + ring-wrap detection → новая concurrency complexity[^9][^10]
- Новая внешняя зависимость в agent classloader → classloading риски
- Нет JMH evidence, что Disruptor даст выигрыш при текущих нагрузках; доказательство необходимо **до** рассмотрения[^6]

***

## 4. Recommended PR Sequence

### PR-0 — Характеризационные тесты и JMH baseline

|  |  |
| :-- | :-- |
| **Scope** | Только тесты, никаких изменений в production коде |
| **Files** | `PlatformDropOldestExportSpanProcessorConcurrencyCharacterizationTest.java` (новый); `NeverCompleteShutdownExporter`, `ThrowingExporter(n)` doubles; JMH baseline file |
| **Tests** | M1 (HIGH): concurrent forceFlush+shutdown race; M2 (HIGH): worker death от unchecked exception; M4 (MEDIUM): exporter.shutdown hang; M3 (MEDIUM): multiple concurrent forceFlush; M7 (LOW): interrupt path; M9 (LOW): forceFlush после completed shutdown |
| **Merge criteria** | M1–M3, M7, M9 GREEN; M4 GREEN или явно `@Disabled("exposes R1")`; JMH baseline зафиксирован |
| **Rollback** | Нельзя откатить — это только тесты; если тест находит баг — исправляем в PR-1 |

### PR-1 — V3 + V1 targeted fixes

|  |  |
| :-- | :-- |
| **Scope** | `ProcessorConfig` record + `Builder` как facade; bounded timeout для `exporter.shutdown()` |
| **Files** | `ProcessorConfig.java` (новый); `PlatformDropOldestExportSpanProcessor.java` (рефактор constructor) |
| **Tests** | Все 5 builder tests GREEN; M4 теперь GREEN; reflection source-compat Builder тест |
| **Merge criteria** | Все существующие тесты GREEN; JMH без регрессии; Builder API не изменился |
| **Rollback** | Если любой builder/lifecycle тест RED — не мерджим |

### PR-2 — V2: Extract TimedExporter

|  |  |
| :-- | :-- |
| **Scope** | `TimedExporter` (package-private); `exportBatch()`, оба counter, `logExportFailureOnce` |
| **Files** | `TimedExporter.java` (новый); delegation в основном классе |
| **Tests** | M6 GREEN до merge; existing lifecycle tests GREEN; `exportTimeouts ⊆ exportFailures` smoke |
| **Merge criteria** | Все тесты GREEN; JMH без регрессии; JMX getter delegation корректна |
| **Rollback** | Если counter values отличаются от ожидаемых — не мерджим |

### PR-3 — V4: BoundedDropOldestQueue + ExportWorker co-design

|  |  |
| :-- | :-- |
| **Scope** | C1+C2 **вместе** в одном PR; переработка interrupt path (R2) |
| **Files** | `BoundedDropOldestQueue.java` (новый); реструктуризация `workerLoop` |
| **Tests** | M1, M2, M7 GREEN до merge; SP-05 все GREEN; SP-05T2 (non-blocking onEnd) GREEN; JMH без регрессии |
| **Merge criteria** | Все concurrency tests GREEN; JMH p99 не деградирует >5%; два reviewer с concurrency expertise |
| **Rollback** | Если любой SP-05 или M1/M2/M7 RED — не мерджим |

### PR-4 — Cleanup + ADR update

|  |  |
| :-- | :-- |
| **Scope** | ADR обновлён; Javadoc для всех новых компонентов; удаление R1–R5 FIXME; taxonomy update |
| **Merge criteria** | ADR актуален; нет FIXME с risk tags; все публичные классы с Javadoc |


***

## 5. What Not To Do

1. **Не менять queue primitive** (ArrayDeque → BlockingDeque) без full co-design C1+C2 — риск нарушения non-blocking onEnd[^4][^5]
2. **Не извлекать ExportWorker (C2) отдельно от BoundedDropOldestQueue (C1)** — interrupt path использует `queueLock` напрямую[^3][^5]
3. **Не добавлять Disruptor dependency** — over-engineering без JMH evidence[^9]
4. **Не менять семантику `forceFlush` после shutdown** — нарушение OTel SDK contract, потенциальный deadlock[^11][^7]
5. **Не рефакторить V4/V6 до появления M1, M2, M7 тестов**[^1]
6. **Не делать full V6 decomposition как один PR**[^3]
7. **Не трогать JMX getter имена, типы, сигнатуры** — JMX wire contract[^12][^4]
8. **Не сбрасывать `exportFailureLogged`** — изменение observable monitoring поведения[^7][^4]
9. **Не переносить `shutdownRequested` double-check из lock-секции** без явного теста на R10 race[^7]
10. **Не объединять V2+V3+C7+C8 в один PR** — слишком большой diff для корректного review[^6]

***

## 6. Final Recommendation — Decision Memo

**Решение:** Рефакторинг разрешён, но только по указанному пути.

Класс содержит **реальные production failures**, не абстрактный technical debt: зависание OTel SDK shutdown (R1), потеря forceFlush promise (R3), необнаруженная смерть worker (R4) — все три могут сигнализировать или молчать в production.[^2][^5]

Начинать нужно с исправления этих рисков (PR-1, V1 fixes), но только после создания characterization tests (PR-0). Структурный refactoring допустим через V3 → V2, они дают реальную ценность без concurrency risk.

**V4 и V6 — следующий уровень**, допустимый только после M1/M2/M7 тестов в проде, V2+V3 в проде, JMH baseline зафиксирован.

**Граница первого PR:** только `ProcessorConfig` record + bounded timeout для `exporter.shutdown()`. Никаких concurrency изменений.

**Абсолютные запреты без board-level sign-off:** изменение JMX getter signatures, изменение Builder method signatures, изменение `forceFlush`-after-shutdown semantics.[^12][^4]

<div align="center">⁂</div>

[^1]: 05-test-coverage-and-gaps.md

[^2]: 00-executive-summary.md

[^3]: 04-responsibility-decomposition.md

[^4]: 06-refactoring-constraints.md

[^5]: 02-concurrency-model.md

[^6]: 07-llm-research-input.md

[^7]: 01-current-behavior.md

[^8]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingDeque.html

[^9]: https://lmax-exchange.github.io/disruptor/user-guide/index.html

[^10]: https://lmax-exchange.github.io/disruptor/javadoc/com.lmax.disruptor/com/lmax/disruptor/class-use/RingBuffer.html

[^11]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk/0.7.1/io/opentelemetry/sdk/trace/SpanProcessor.html

[^12]: 03-adjacent-code-map.md

