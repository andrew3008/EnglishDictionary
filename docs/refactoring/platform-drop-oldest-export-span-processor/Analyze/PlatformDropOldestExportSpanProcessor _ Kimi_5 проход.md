<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are Kimi Thinking inside Perplexity.

Role:
Act as an adversarial reviewer and concurrency bug hunter.

Your job:
Try to break the proposed refactoring ideas and the current implementation mentally. Search for hidden races, lifecycle bugs, semantic ambiguities, test gaps, and false assumptions.

Context:
`PlatformDropOldestExportSpanProcessor` is a custom OpenTelemetry Java SpanProcessor with:

- bounded drop-oldest queue
- worker thread
- forceFlush promises
- shutdown terminator thread
- export timeout
- exporter.shutdown delegation
- JMX counters
- Builder config fallback

Attached files:
Use the source code and all MD investigation files as evidence.

Important:
You are not asked to produce the most pleasant architecture. You are asked to find what can go wrong.

Focus areas:

1. `forceFlush + shutdown` race
2. worker exits before pending flush is processed
3. exporter shutdown never completes
4. worker interrupted while waiting
5. worker interrupted while exporting
6. exporter throws RuntimeException
7. exporter returns failed `CompletableResultCode`
8. exporter returns never-completing `CompletableResultCode`
9. `toSpanData()` throws
10. queue full while shutdown begins
11. multiple forceFlush callers
12. clock/timing issues with `System.nanoTime`
13. spurious wakeups
14. log throttling hides production issues
15. JMX getters racing with shutdown
16. Builder invalid config fallback
17. direct use outside factory constraints
18. multi-exporter fallback assumptions
19. daemon thread lifecycle

Task:
Produce an adversarial review of the current design and each of the 8 variants.

For each issue:

- scenario
- exact trigger sequence
- expected current behavior
- possible bad behavior
- whether tests cover it
- what characterization test is needed
- whether refactoring increases or reduces the risk
- severity
- probability
- detectability

Output in Russian:

## 1. Executive Adversarial Verdict

- top 10 ways this can fail
- which failure is most likely in production
- which failure is most catastrophic


## 2. Current Implementation Failure Scenarios

At least 15 scenarios.
Use step-by-step sequences.

## 3. Variant Risk Review

For V1–V8:

- new risks introduced
- old risks removed
- risks unchanged
- adversarial verdict


## 4. Race Condition Catalogue

Create a table:

- race
- actors
- sequence
- bad outcome
- test strategy
- fix strategy


## 5. Test Gap Attack Plan

Prioritize tests that would catch the most dangerous failures.

## 6. Red Flags for Code Review

List exact code patterns that reviewers must reject during refactoring.

## 7. Final Adversarial Recommendation

State what must be proven before queue/worker extraction is allowed.

Be strict and skeptical. Assume production traffic, slow exporters, shutdown under pressure, and concurrent forceFlush calls.

Короткий вывод: текущая реализация уже содержит несколько реальных production-failure modes, и часть “красивых” рефакторинговых идей легко делает ситуацию хуже, а не лучше; до извлечения queue/worker нужно сначала доказать тестами, что не ломаются `forceFlush`, `shutdown`, non-blocking `onEnd` и JMX‑совместимость. Самые опасные зоны — `forceFlush + shutdown`, `exporter.shutdown()` без второго таймаута, interrupt-path в `workerLoop`, и смерть worker от unchecked exception.[^1][^2][^3][^4][^5]

## 1. Executive Adversarial Verdict

- Топ‑10 способов, которыми система может сломаться:

1. `exporter.shutdown()` никогда не завершится, `shutdownResult` останется незавершённым, а graceful shutdown SDK зависнет.[^2][^5][^1]
2. `forceFlush` добавит promise в `pendingFlushes`, но worker уже выйдет, и promise никогда не завершится.[^4][^5][^2]
3. Refactoring interrupt-path повторно внесёт double-unlock или lock leak из-за раннего `queueLock.unlock()` и `isHeldByCurrentThread()` в `finally`.[^1][^2]
4. Worker умрёт от unchecked exception и экспорт навсегда прекратится без recovery.[^5][^4][^1]
5. `exporter.export()` вернёт never-completing `CompletableResultCode`, и система будет жить только за счёт export timeout, а при shutdown упрётся в `exporter.shutdown()` hang.[^5][^1]
6. Постоянный one-shot throttle `logExportFailureOnce` скроет повторные сбои после восстановления экспортера.[^3][^1][^5]
7. Во время начала shutdown producer успеет пройти fast-path и положить span в queue, после чего span может считаться overflow, а не after-shutdown loss; семантика ambiguous.[^1][^5]
8. Несколько concurrent `forceFlush` вызовов могут повиснуть или завершиться несимметрично, потому что этот путь не покрыт отдельным тестом.[^2][^4]
9. Извлечение queue без co-design worker сломает атомарность `pollFirst()` + `droppedSpansOverflow.incrementAndGet()`.[^6][^3]
10. Прямая замена на `BlockingDeque` или другой primitive может сделать `onEnd` блокирующим, что ломает главный producer invariant.[^7][^3]
- **Наиболее вероятный production failure:** скрытые повторные export failures после первого WARN, потому что exporter может флапать, а `logExportFailureOnce` больше ничего не покажет, хотя counters будут расти.[^3][^5][^1]
- **Наиболее катастрофический failure:** незавершающийся `exporter.shutdown()`, который оставляет `shutdownResult` незавершённым и способен подвесить shutdown OpenTelemetry SDK и процесса в контролируемом завершении.[^2][^5][^1]


## 2. Current Implementation Failure Scenarios

Ниже — 15 adversarial сценариев с шагами, текущим ожидаемым поведением и возможным bad outcome.[^4][^5][^2]

### S1. `forceFlush` promise теряется на гонке с `shutdown`

- **Scenario:** `forceFlush()` и `shutdown()` вызываются почти одновременно.[^5][^2]
- **Trigger sequence:**

1. T1 вызывает `forceFlush()`, проходит `shutdownRequested.get() == false`, создаёт `CompletableResultCode` и добавляет его в `pendingFlushes` под `queueLock`.[^2][^5]
2. T2 вызывает `shutdown()`, делает `compareAndSet(false, true)`, будит worker и запускает terminator thread.[^5]
3. Worker успевает увидеть shutdown, завершить цикл или войти в shutdown-drain path не обработав новый flush promise.[^2]
- **Expected current behavior:** flush promise должен быть завершён success/fail в worker cycle.[^5]
- **Possible bad behavior:** promise остаётся незавершённым навсегда, вызывающий поток, который ждёт `join()`, зависает по бизнес-логике выше.[^4][^2][^5]
- **Coverage:** нет прямого теста; это M1 gap.[^4]
- **Needed test:** two-thread coordinated race с latch/barrier и bounded assertion “all flush results complete or fail, never hang”.[^4]
- **Refactoring effect:** V4/V6 увеличивают риск; V1 targeted hardening и stateful shutdown coordination уменьшают риск.[^6][^7][^2]
- **Severity / Probability / Detectability:** High / Medium / Low.[^1][^2]


### S2. Worker выходит раньше, чем обработает `pendingFlushes`

- **Scenario:** queue пустая, но есть late flush promise перед остановкой worker.[^2]
- **Trigger sequence:**

1. Worker завершает shutdown drain и собирается выйти.[^5]
2. Новый `forceFlush()` проходит fast-path до того, как `shutdownRequested` замечен вызывающим потоком или после этого при race timing.[^2]
3. Promise попадает в список, но worker уже не вернётся в цикл.[^2]
- **Expected current behavior:** после shutdown `forceFlush()` должен сразу вернуть success, а до shutdown — promise должен завершиться.[^3][^5]
- **Possible bad behavior:** незавершённый flush promise, silent hang на уровне SDK/интеграции.[^4][^2]
- **Coverage:** нет; частично смежно с M1 и M9.[^4]
- **Needed test:** worker-exit timing test + post-shutdown flush call test.[^4]
- **Refactoring effect:** V6 повышает риск, если lifecycle coordinator не формализован; V3/V2 риск почти не меняют.[^7][^6]
- **Severity / Probability / Detectability:** High / Low-Medium / Low.[^2]


### S3. `exporter.shutdown()` never completes

- **Scenario:** exporter возвращает `CompletableResultCode`, который не завершится никогда.[^5][^4]
- **Trigger sequence:**

1. `shutdown()` запускает terminator thread.[^5]
2. Terminator ждёт `workerTerminated` до `shutdownTimeoutNanos`.[^5][^2]
3. После этого вызывает `exporter.shutdown()` без второго таймаута.[^1][^5]
- **Expected current behavior:** shutdown должен завершиться и вернуть один и тот же `CompletableResultCode` идемпотентно.[^3][^5]
- **Possible bad behavior:** terminator daemon thread течёт, `shutdownResult` never completes, а `SdkTracerProvider.shutdown()` или caller поверх этого ждёт бесконечно.[^1][^2][^5]
- **Coverage:** отсутствует; M4 прямо описывает этот gap.[^4]
- **Needed test:** `NeverCompleteShutdownExporter` + bounded test timeout.[^4]
- **Refactoring effect:** V1 может закрыть риск; V6/V4 без targeted fix сохраняют его; V8/V7 его не решают автоматически.[^7][^2]
- **Severity / Probability / Detectability:** Critical / Medium / Medium.[^1]


### S4. Worker interrupted while waiting (`awaitNanos`)

- **Scenario:** terminator timeout вызывает `workerThread.interrupt()` пока worker ждёт на `Condition.awaitNanos()`.[^2]
- **Trigger sequence:**

1. Worker заходит в wait loop с lock.[^2]
2. Terminator по timeout вызывает interrupt.[^5][^2]
3. `InterruptedException` попадает в catch, код делает `Thread.currentThread().interrupt()`, `drainBatchLocked()`, `drainPendingFlushesLocked()`, затем **ранний `queueLock.unlock()`**, потом `tryExport(...)`, потом `return`.[^2]
- **Expected current behavior:** lock должен быть освобождён ровно один раз, batch и promises должны корректно завершиться.[^2]
- **Possible bad behavior:** при любом неаккуратном изменении структуры блока — double-unlock, `IllegalMonitorStateException`, lock leak или блокировка producers навсегда.[^1][^2]
- **Coverage:** изолированного теста нет; это M7 gap.[^4]
- **Needed test:** deterministic interrupt-path test с маленьким `shutdownTimeout` и blocked exporter/await path.[^4]
- **Refactoring effect:** V4/V6 сильно увеличивают риск; V1/V2/V3 почти не меняют риск.[^6][^2]
- **Severity / Probability / Detectability:** High / Medium / Medium.[^2]


### S5. Worker interrupted while exporting

- **Scenario:** worker уже не ждёт на condition, а находится внутри `exportBatch(...).join(timeout)`.[^2]
- **Trigger sequence:**

1. `exporter.export(batch)` возвращает `CompletableResultCode`.[^5]
2. Worker вызывает `join(exportTimeoutNanos, NANOSECONDS)`.[^5]
3. Terminator делает `workerThread.interrupt()` во время join.[^2]
- **Expected current behavior:** timeout path должен аккуратно сработать и инкрементировать timeout/failure counters.[^5]
- **Possible bad behavior:** interrupt не является первичным контролем этого пути; код может вести себя как “обычный timeout”, но reviewer легко сделает ложное предположение, что interrupt немедленно разбудит export join и ускорит drain.[^2]
- **Coverage:** прямого теста нет.[^4]
- **Needed test:** exporter, который не completes export result, плюс interrupt during export observation.[^4]
- **Refactoring effect:** V7/V8 и worker rewrite увеличивают риск ложной семантики; V2 хорошо локализует этот путь.[^6][^7]
- **Severity / Probability / Detectability:** Medium / Medium / Low.[^2]


### S6. `exporter.export()` throws `RuntimeException`

- **Scenario:** exporter падает синхронно при вызове `export(batch)`.[^5][^4]
- **Trigger sequence:** worker вызывает `exporter.export(batch)`, exporter бросает `RuntimeException`.[^5]
- **Expected current behavior:** `exportFailures++`, один WARN, worker продолжает жить.[^5]
- **Possible bad behavior:** если refactoring случайно вынесет try/catch не туда, worker может умереть или promise forceFlush завершится неверно.[^6][^5]
- **Coverage:** есть partial coverage `exporterExceptionIsolatedAndCounted`.[^8][^4]
- **Needed test:** оставить существующий test как regression lock, плюс verify forceFlush promises completed on exporter exception.[^4]
- **Refactoring effect:** V2 снижает риск через изоляцию export logic; V6/V4 могут переоткрыть risk if exception boundary shifts.[^6]
- **Severity / Probability / Detectability:** Medium / Medium / High.[^4]


### S7. `exporter.export()` returns failed `CompletableResultCode`

- **Scenario:** export result completes, but `isSuccess() == false`.[^5]
- **Trigger sequence:** worker вызывает export, result completes failed.[^5]
- **Expected current behavior:** `exportFailures++`, WARN only once, worker continues.[^5]
- **Possible bad behavior:** repeated failures после одного recovery-window не логируются вообще, и SRE видит только counters/JMX, если вообще смотрит туда.[^3][^1][^5]
- **Coverage:** частично покрыто exception/timeout tests, но не repeated failed result logging semantics.[^4]
- **Needed test:** repeated failed result with intermittent success, validate counters and logging blindness.[^4]
- **Refactoring effect:** V2 может локализовать risk; changing `logExportFailureOnce` policy silently changes monitoring contract.[^3][^6]
- **Severity / Probability / Detectability:** Medium / High / Low.[^1]


### S8. `exporter.export()` returns never-completing `CompletableResultCode`

- **Scenario:** export hangs forever, but `exportTimeoutNanos` exists.[^5]
- **Trigger sequence:** worker invokes export, result never completes, join times out.[^5]
- **Expected current behavior:** `exportTimeouts++`, `exportFailures++`, worker keeps running.[^5]
- **Possible bad behavior:** if exporter keeps hanging every time, queue pressure becomes permanent, many spans are evicted, one WARN only, and system silently degrades to continuous loss mode.[^1][^5]
- **Coverage:** timeout counter path covered, but prolonged pathological repeated timeout mode not well covered.[^4]
- **Needed test:** repeated hung exports under sustained pressure with overflow counter assertions.[^4]
- **Refactoring effect:** V1/V2 can harden visibility; V7/V8 do not address semantic degradation.[^7]
- **Severity / Probability / Detectability:** High / Medium / Low.[^1]


### S9. `toSpanData()` throws

- **Scenario:** `ReadableSpan.toSpanData()` throws on producer thread.[^4][^5]
- **Trigger sequence:** sampled span ends, `onEnd` calls `span.toSpanData()`, method throws `RuntimeException`.[^5]
- **Expected current behavior:** one WARN via `logExportFailureOnce`, span lost, no dedicated counter increment.[^5]
- **Possible bad behavior:** data loss is invisible in taxonomy because neither overflow nor after-shutdown counter changes; same global one-shot throttle may hide all subsequent snapshot failures too.[^1][^5]
- **Coverage:** missing; this is M8 gap.[^4]
- **Needed test:** fake `ReadableSpan` throwing from `toSpanData()`, then verify processor continues for subsequent spans.[^4]
- **Refactoring effect:** any change that moves logging policy or exception handling can silently widen blind spot.[^3][^6]
- **Severity / Probability / Detectability:** Medium / Low / Very Low.[^4]


### S10. Queue full while shutdown begins

- **Scenario:** queue saturated, producer threads still calling `onEnd`, and `shutdown()` starts concurrently.[^2][^5]
- **Trigger sequence:**

1. Producers enter `onEnd` with queue already full.[^5]
2. Another thread flips `shutdownRequested`.[^5]
3. Some producers pass fast-path before the flag, some hit the inside-lock double-check after the flag.[^5]
- **Expected current behavior:** spans after shutdown should count in `droppedSpansAfterShutdown`; spans before should either enqueue or overflow deterministically.[^5]
- **Possible bad behavior:** race causes accounting ambiguity: one span can be accepted then later cleared on timeout or counted as overflow instead of after-shutdown drop.[^1]
- **Coverage:** overflow and shutdown covered separately, but not combined concurrent race.[^4]
- **Needed test:** saturated queue + concurrent shutdown + assertions on counter partitions.[^4]
- **Refactoring effect:** V4/V6 increase risk if shutdown double-check moves out of lock or queue abstraction hides it.[^6][^3]
- **Severity / Probability / Detectability:** Medium / Medium / Medium.[^1]


### S11. Multiple concurrent `forceFlush` callers

- **Scenario:** many threads call `forceFlush()` simultaneously.[^2][^4]
- **Trigger sequence:** 10 threads create promises concurrently, all add to `pendingFlushes` under same lock, worker drains all in one or few cycles.[^2]
- **Expected current behavior:** all returned `CompletableResultCode` objects eventually complete success/fail, none hang.[^2]
- **Possible bad behavior:** subset completes, subset hangs, or completion order/visibility bug appears during shutdown or export exception path.[^4]
- **Coverage:** missing; M3 gap.[^4]
- **Needed test:** barrier-based 10-thread `forceFlush` storm.[^4]
- **Refactoring effect:** V6 can improve this only if explicit coordinator is correct; V4 can make it worse via split lock ownership.[^6][^2]
- **Severity / Probability / Detectability:** High / Medium / Low.[^4]


### S12. `System.nanoTime()` / timing corner cases

- **Scenario:** long pauses, scheduler delays, or large elapsed values affect `scheduleDelay` logic.[^2][^4]
- **Trigger sequence:** worker computes `elapsedSinceExport = now - lastExportNanos`; GC pause or CPU starvation makes elapsed huge.[^2]
- **Expected current behavior:** worker should simply export sooner once it wakes.[^2]
- **Possible bad behavior:** tests that assume crisp timing become flaky; refactoring to executor/scheduler can accidentally change “elapsed scheduleDelay with non-empty queue” semantics.[^7][^4]
- **Coverage:** deterministic scheduleDelay flush test missing, M5 gap.[^4]
- **Needed test:** short `scheduleDelay`, under-batch queue, bounded wait and assert export occurred without explicit signal.[^4]
- **Refactoring effect:** V7/executor-style alternatives increase risk by changing scheduling semantics; V1/V3 no impact.[^7]
- **Severity / Probability / Detectability:** Low-Medium / Medium / Medium.[^4]


### S13. Spurious wakeups

- **Scenario:** `Condition.awaitNanos()` wakes spuriously.[^2]
- **Trigger sequence:** worker wakes without signal and without enough elapsed time.[^2]
- **Expected current behavior:** while-loop should re-evaluate `shouldExportNow` and continue waiting safely.[^2]
- **Possible bad behavior:** refactoring to helper methods or queue abstraction can accidentally weaken the loop condition or misuse remaining nanos, causing spin or premature export.[^2]
- **Coverage:** no direct test, though current code structure is intended to handle it.[^2][^4]
- **Needed test:** difficult to force directly; use abstraction-preserving review rule plus scheduleDelay characterization.[^4]
- **Refactoring effect:** V4/V6 increase risk because they likely reshape wait loop structure.[^6][^2]
- **Severity / Probability / Detectability:** Medium / Low / Low.[^2]


### S14. Log throttling hides recurring production incidents

- **Scenario:** exporter fails once, recovers, then fails again later.[^1][^5]
- **Trigger sequence:** first failure sets `exportFailureLogged=true`; subsequent failures only increment counters.[^5]
- **Expected current behavior:** exactly one WARN forever; this is current observable behavior.[^3]
- **Possible bad behavior:** operations teams miss second incident entirely if they rely on logs more than JMX/metrics.[^3][^1]
- **Coverage:** missing; M6 gap.[^4]
- **Needed test:** capture log appender, fail exporter 10 times, assert one WARN only.[^4]
- **Refactoring effect:** V2 may accidentally change policy; V1 can leave it as-is or deliberately change with ADR, but silent change is dangerous.[^3][^6]
- **Severity / Probability / Detectability:** Medium / High / Low.[^1]


### S15. JMX getters race with shutdown / queue clear

- **Scenario:** JMX reads `getQueueSize()` while terminator clears queue after timeout.[^3][^5]
- **Trigger sequence:** shutdown timeout path acquires `queueLock`, adds queue size to `droppedSpansAfterShutdown`, clears queue; JMX thread concurrently calls getters.[^5]
- **Expected current behavior:** per-getter thread safety only, not a consistent multi-counter snapshot.[^6][^3]
- **Possible bad behavior:** operators see transiently inconsistent combinations, e.g. `queueSize=0` with stale counters or vice versa; a refactoring that promises “snapshot semantics” could accidentally change contract.[^6][^3]
- **Coverage:** no dedicated source-compat or semantics test for getters.[^1][^4]
- **Needed test:** reflection/source-compat test + concurrency smoke around getters during shutdown timeout path.[^3][^4]
- **Refactoring effect:** C7 extraction can help if it preserves semantics; changing to a “consistent snapshot” would actually be a behavior change.[^6]
- **Severity / Probability / Detectability:** Low-Medium / Medium / High.[^6]


### S16. Builder invalid config fallback masks bad rollout

- **Scenario:** config typo sets zero/negative values, system silently falls back to defaults.[^5]
- **Trigger sequence:** operator misconfigures queue size / export timeout / schedule delay.[^5]
- **Expected current behavior:** WARN + production-safe fallback, except null exporter which is fail-fast.[^3][^5]
- **Possible bad behavior:** misconfigured rollout appears “healthy” but runs with unexpected defaults, making incident diagnosis harder.[^5]
- **Coverage:** builder validation is well-covered, but operational observability of fallback correctness is limited.[^4]
- **Needed test:** existing tests are adequate for semantics; missing piece is maybe log/assert for WARN messages, if considered important.[^4]
- **Refactoring effect:** V3 can accidentally change fallback semantics; reviewers must reject any fail-fast expansion.[^3][^6]
- **Severity / Probability / Detectability:** Medium / Medium / Medium.[^5]


### S17. Direct use outside factory constraints

- **Scenario:** class is instantiated directly outside `PlatformExportProcessorFactory`, possibly with multi-fan-out exporter or different lifecycle assumptions.[^8][^1]
- **Trigger sequence:** tests or future code call `builder(exporter).build()` directly with a nonstandard exporter topology.[^8]
- **Expected current behavior:** processor assumes single-exporter wrapping and factory-enforced opt-in activation.[^8][^7]
- **Possible bad behavior:** shutdown drain semantics may only really correspond to one downstream transport or violate hidden integration assumptions.[^8][^1]
- **Coverage:** factory-level integration tests cover opt-in and multi-exporter fallback externally, but processor itself has no internal guard.[^8][^4]
- **Needed test:** direct-construction misuse test or explicit ADR statement; at minimum document unsupported direct topologies.[^8]
- **Refactoring effect:** V6 or exporter decorators may obscure this assumption further unless documented.[^7]
- **Severity / Probability / Detectability:** Medium / Low / Medium.[^1]


### S18. Daemon thread lifecycle masks incomplete drain on process exit

- **Scenario:** JVM exits without orderly SDK shutdown while worker/terminator are daemon threads.[^3][^5]
- **Trigger sequence:** process terminates abruptly or orderly shutdown hooks are bypassed; daemon threads do not keep JVM alive.[^3]
- **Expected current behavior:** daemon threads intentionally should not block process exit.[^3][^5]
- **Possible bad behavior:** spans in queue are silently lost, and no shutdown path completes.[^5]
- **Coverage:** this is design intent rather than a bug, but easy to forget during refactoring to non-daemon executors.[^3]
- **Needed test:** source-level assertion that worker remains daemon or equivalent non-blocking lifecycle property preserved.[^3]
- **Refactoring effect:** executor-based worker alternatives increase risk of accidentally making shutdown behavior worse by keeping JVM alive.[^7][^3]
- **Severity / Probability / Detectability:** Medium / Medium / High.[^3]


## 3. Variant Risk Review

| Variant | New risks introduced | Old risks removed | Risks unchanged | Adversarial verdict |
| :-- | :-- | :-- | :-- | :-- |
| V1 | Minimal new risks; main risk is false confidence from “fixed enough” monolith.[^1][^7] | Can remove R1 and maybe R3 if explicitly hardened.[^1][^2] | R2, R4, observability blind spots mostly remain.[^1] | **GO only as hardening**, not as architectural end state.[^7] |
| V2 | Counter ownership split, duplicated/changed throttle semantics, wrong delegation in JMX getters.[^6][^3] | Localizes export exception/timeout logic and makes it easier to test failure classification.[^6][^4] | Queue/worker races remain.[^2] | **GO with M6 and counter regression tests first.**[^4] |
| V3 | Builder facade may accidentally alter WARN+fallback behavior or BSP key compatibility.[^5][^3] | Removes constructor/config clutter safely.[^6] | All runtime concurrency risks remain.[^2] | **Safest first structural PR.**[^6][^3] |
| V4 | Cross-object lock ownership, broken eviction atomicity, broken shutdown double-check, reintroduced double-unlock.[^6][^2][^3] | Could eventually isolate queue behavior and reduce local complexity.[^6] | R3 may remain unless flush coordination also redesigned.[^2] | **CAUTION; no queue extraction before M1/M2/M7 are green.**[^4] |
| V5 | Review scope inflation: several safe changes together hide one unsafe semantic change.[^7] | Removes low-risk responsibilities from monolith.[^6][^7] | Worker/queue core hazards remain.[^2] | **Good target state, bad giant PR.**[^7] |
| V6 | Big-bang decomposition can break lifecycle invariants at multiple seams simultaneously.[^6][^7] | Best chance to eventually isolate queue/worker/flush/shutdown responsibilities cleanly.[^6] | None automatically; everything must be re-proven.[^3] | **Long-term only; dangerous before smaller extractions land.**[^6][^4] |
| V7 | May make `onEnd` blocking or implement non-atomic drop-oldest loop; new scheduling semantics emerge.[^7][^3] | Might remove explicit `Condition`/unlock pattern syntactically.[^7] | forceFlush/shutdown coupling remains conceptually hard.[^2] | **NO GO; too easy to break the core contract.**[^3][^7] |
| V8 | Redesign risk, dependency/classloader risk, new overflow semantics, complex shutdown/flush translation.[^7][^8] | Could remove current manual lock model entirely.[^7] | Observability and SDK contract proof burden remains.[^3] | **NO GO unless a benchmark-first redesign is explicitly approved.**[^7] |

## 4. Race Condition Catalogue

| Race | Actors | Sequence | Bad outcome | Test strategy | Fix strategy |
| :-- | :-- | :-- | :-- | :-- | :-- |
| Flush-vs-shutdown registration race | `forceFlush` caller, shutdown caller, worker | promise added, shutdown flips flag, worker exits | uncompleted flush promise | M1 two-thread latch race | explicit lifecycle state / drain-pending-on-exit guarantee.[^2][^4] |
| Worker interrupt wait-path race | terminator, worker | interrupt during `awaitNanos`, early unlock path runs | double-unlock or lock leak after refactor | M7 interrupt isolation | restructure wait/export boundary, no manual unlock outside clear contract.[^2][^4] |
| Queue full + shutdown fast-path race | producer, shutdown caller | producer passes fast-path, shutdown flips, enqueue path continues | ambiguous accounting overflow vs after-shutdown | saturated queue + concurrent shutdown test | preserve inside-lock double-check; document semantics if unchanged.[^5][^1] |
| Multiple `forceFlush` callers | many callers, worker | promises accumulate under same lock | subset hangs or mis-completes | M3 barrier-based flush storm | explicit flush coordinator or complete-all-on-shutdown invariant.[^2][^4] |
| Export-hang + shutdown-hang compound race | worker, terminator, exporter | export times out repeatedly, then shutdown never completes | SDK shutdown hang after degraded export period | hung export then hung shutdown test doubles | bounded shutdown adapter / second timeout.[^5][^1][^4] |
| Worker death after unexpected exception | worker, shutdown caller | unchecked exception terminates loop, later shutdown waits | exports stop silently, lifecycle ambiguous | M2 controlled exception injection | fail-fast state, explicit terminal flag, guaranteed latch countdown.[^2][^4] |
| Getter-vs-clear visibility race | JMX reader, terminator | queue cleared while getters read | transient inconsistent observability | getter concurrency smoke | preserve per-getter semantics; don’t promise snapshot consistency.[^6][^3] |

## 5. Test Gap Attack Plan

Приоритет тестов по “ожидаемая опасность × шанс поймать реальный баг” такой:[^1][^4]

1. **M1 — concurrent `forceFlush + shutdown` race.** Это лучший тест на R3 и одновременно gate для любого queue/worker/lifecycle refactoring.[^2][^4]
2. **M4 — `exporter.shutdown()` hang.** Это ловит самый катастрофический shutdown failure, который способен повесить SDK shutdown целиком.[^1][^4]
3. **M2 — worker death from unchecked exception.** Это выявляет “экспорт тихо умер навсегда” и доказывает корректность `workerTerminated.countDown()` в exceptional path.[^2][^4]
4. **M3 — multiple concurrent `forceFlush`.** Ловит класс hanging-promise ошибок, которые иначе легко пропустить в smoke tests.[^4]
5. **M7 — interrupt during await path.** Это прямой тест на самую хрупкую часть lock choreography.[^2][^4]
6. **M6 — log throttling verification.** Это не спасает корректность данных, но спасает observability assumptions и предотвращает silent monitoring regressions.[^3][^4]
7. **M8 — `toSpanData()` runtime exception path.** Ловит неучтённые потери и проверяет, что processor продолжает работать после producer-side exception.[^5][^4]
8. **M5 — deterministic scheduleDelay export.** Нужен перед любым scheduler/executor refactoring или reshaping wait loop.[^4]

## 6. Red Flags for Code Review

Ниже паттерны, которые reviewer должен отклонять немедленно:[^6][^3][^2]

- Любой код, где `exporter.export()` снова оказывается под lock, который также нужен producer path.[^3][^5]
- Любой PR, который выносит queue в отдельный класс, но оставляет worker владеть её lock косвенно или вручную обращаться к её `ReentrantLock` без жёстко определённого контракта.[^6][^2]
- Любой рефактор interrupt-path, где unlock происходит больше чем в одном месте или correctness доказывается фразой “ну тут guard спасёт”.[^2]
- Любая попытка заменить `ArrayDeque + ReentrantLock` на `BlockingDeque`/`LinkedBlockingDeque` без доказательства неблокирующего `onEnd` и атомарного drop-oldest.[^7][^3]
- Любое изменение `forceFlush()` после shutdown, которое перестаёт немедленно возвращать success.[^3][^5]
- Любое изменение JMX getter signatures, return types или semantics под видом “clean API”.[^8][^3]
- Любое изменение Builder API, `build()` return type или BSP-key compatibility (`otel.bsp.*`).[^8][^3]
- Любое “улучшение” `logExportFailureOnce`, которое silently меняет observable behavior без ADR и теста на новую политику.[^3][^4]
- Любой большой PR, который одновременно трогает queue, worker, flush coordination и shutdown coordination до появления M1/M2/M7.[^7][^4]
- Любой executor/scheduler rewrite без повторной фиксации QueueOfferBenchmark и deterministic `scheduleDelay` behavior.[^8][^3][^4]


## 7. Final Adversarial Recommendation

До извлечения queue/worker должно быть **доказано**, а не предположено, следующее:[^3][^2][^4]

1. `forceFlush` promise **никогда** не остаётся незавершённым при concurrent `shutdown`.[^2][^4]
2. Worker exceptional exit всегда приводит к корректному `workerTerminated.countDown()` и завершению shutdown path.[^2][^4]
3. Interrupt-path не допускает ни double-unlock, ни lock leak, и это подтверждено детерминированным тестом, а не только код-ревью рассуждением.[^2][^4]
4. `exporter.shutdown()` bounded или явно признан допустимым hang‑риском с операционной компенсацией; текущая версия этот риск не закрывает.[^1][^5]
5. `exporter.export()` остаётся вне producer lock во всех ветках, включая shutdown/flush/interrupt paths.[^3][^5]
6. Queue extraction не ломает атомарность `pollFirst()` + increment overflow counter и inside-lock shutdown recheck.[^6][^3]
7. QueueOfferBenchmark steady/saturated не показывает материальной регрессии.[^8][^3]

Жёсткий итог: **queue/worker extraction запрещён, пока M1, M2, M4 и M7 не зелёные, а V3/V2 не доказали, что команда умеет перемещать ответственность без изменения семантики.** Самая опасная ложная идея — считать, что “если код стал чище, значит он стал безопаснее”; для этого класса это, скорее всего, неверно.[^6][^1][^3][^2][^4]

<div align="center">⁂</div>

[^1]: 00-executive-summary.md

[^2]: 02-concurrency-model.md

[^3]: 06-refactoring-constraints.md

[^4]: 05-test-coverage-and-gaps.md

[^5]: 01-current-behavior.md

[^6]: 04-responsibility-decomposition.md

[^7]: 07-llm-research-input.md

[^8]: 03-adjacent-code-map.md

