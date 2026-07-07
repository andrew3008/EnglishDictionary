---
name: drop-oldest refactoring plan
overview: "Clean-architecture-lite рефакторинг production-critical класса PlatformDropOldestExportSpanProcessor в pre-production режиме (breaking changes допустимы). Целевая архитектура — V6-clean-lite (~7-8 production-классов, без speculative taxonomy): тонкий SpanProcessor-facade, ExportBuffer (владеет queue + pendingFlushes + lock), ExportWorker, bounded ProcessorLifecycle (private shutdown coordination), TimedSpanExporter, DropOldestProcessorMetrics + MetricsSnapshot, DropOldestExportProcessorConfig. Миграция test-first и component-first: PR-0 (тесты + baseline) → PR-1 (минимальные компоненты: config/exporter/metrics) → PR-2 (buffer/worker/lifecycle + facade) → PR-3 (clean shutdown/forceFlush) → PR-4 (замена Builder/API, JMX adapter над snapshot) → PR-5 (удаление legacy) → PR-6 (JMH/stress)."
todos:
  - id: pr0
    content: "PR-0: характеризационные тесты ключевых инвариантов + JMH/stress baseline как safety net (без production-изменений)"
    status: pending
  - id: pr1
    content: "PR-1: минимальные компоненты (DropOldestExportProcessorConfig, TimedSpanExporter, DropOldestProcessorMetrics+Snapshot) + unit-тесты; без buffer/worker/lifecycle и без speculative-классов (§7a)"
    status: pending
  - id: pr2
    content: "PR-2: ввести ExportBuffer/ExportWorker/ProcessorLifecycle, перенести логику; PlatformDropOldestExportSpanProcessor становится thin facade; вспомогательные типы — nested records/enum"
    status: pending
  - id: pr3
    content: "PR-3: clean shutdown/forceFlush semantics — bounded shutdown, deterministic forceFlush, никогда не оставлять CompletableResultCode незавершённым"
    status: pending
  - id: pr4
    content: "PR-4: заменить Builder на config-first builder, 6 getters → MetricsSnapshot; JMX сохранить как adapter (PlatformExportControl читает snapshot); обновить factory wiring в том же PR"
    status: pending
  - id: pr5
    content: "PR-5: удалить legacy/transitional adapters, deprecated методы, старую документацию"
    status: pending
  - id: pr6
    content: "PR-6: JMH + stress валидация новой архитектуры; опубликовать результаты vs baseline"
    status: pending
isProject: false
---

# План рефакторинга `PlatformDropOldestExportSpanProcessor` (V6-clean-lite)

> Статус: Proposed (для staff/principal review). Режим: **pre-production, breaking changes допустимы**. Это план, не реализация — production-код меняется только начиная с PR-1. Целевой файл для публикации: [docs/refactoring/platform-drop-oldest-export-span-processor/08-refactoring-plan.md](docs/refactoring/platform-drop-oldest-export-span-processor/08-refactoring-plan.md).
>
> Решение по стратегии: компонент ещё не вышел в production, поэтому мы **не консервируем текущие публичные формы** (Builder API, 6 JMX getters, текущую shutdown/logging semantics), а проектируем чистую архитектуру. **Исключение:** JMX сохраняется как adapter-слой для операторов (snapshot → JMX attributes).

---

## 1. Executive Summary

- **Целевая архитектура: V6-clean-lite** — clean boundaries для реальной сложности (facade / config / buffer / worker / timed exporter / lifecycle / metrics snapshot / JMX adapter), **БЕЗ speculative class taxonomy**. ~7–8 production-классов, не mini-framework. Это **target architecture, а не conditional future option**.
  - **Счёт классов:** nested records/enums и существующий JMX adapter (`PlatformExportControl`) **не считаются** отдельными архитектурными компонентами.
- **Принцип декомпозиции:** не «механически разнести текущий класс на 18+ классов», а спроектировать чистые границы со state machine и явным lock-ownership. Компонентность — да, framework внутри одного processor'а — нет (см. §7a Anti-Overengineering Rules). Главный инвариант чистоты: **lock ownership не пересекает границы классов неявно**.
- **PR-0 остаётся обязательным**, но теперь как **safety net** для новой архитектуры (фиксирует ключевые инварианты и known failures), а не как тормоз рефакторинга на месяцы.
- **Backward compatibility** для Builder/JMX **не является hard constraint** (pre-production). Старый Builder заменяется на config-first builder; 6 индивидуальных getters заменяются на `MetricsSnapshot`. **JMX сохраняется как operator-facing adapter-слой над `MetricsSnapshot`**; JMX schema может быть переработана под чистую snapshot-модель, старые attribute names не являются constraint (новая schema документируется как pre-production operator contract).
- **Фундаментальная семантика процессора НЕ ломается:** drop-oldest, non-blocking onEnd, exporter вне lock, bounded shutdown, корректный forceFlush, отсутствие deadlock, opt-in wiring, BSP config keys, multi-exporter fallback.
- **Порядок:** PR-0 (tests/baseline) → PR-1 (минимальные компоненты: config/exporter/metrics) → PR-2 (buffer/worker/lifecycle + facade) → PR-3 (clean lifecycle semantics) → PR-4 (replace API, JMX adapter, factory) → PR-5 (remove legacy) → PR-6 (JMH/stress).

### Топ-5 архитектурных решений
1. **Thin facade.** `PlatformDropOldestExportSpanProcessor` не содержит queue/worker/shutdown/timeout/builder-логики — только делегирование в `ProcessorLifecycle`/`DropOldestProcessorMetrics`.
2. **`ExportBuffer` — синхронизированный буфер экспортной работы**, владеющий одновременно queue, pending flush requests, `Condition`/signal и lock ownership. forceFlush НЕ извлекается в отдельный координатор, живущий отдельно от lock.
3. **Lock-ownership не утекает.** `ExportWorker.awaitWork()` получает готовый `ExportWork`, а не «lock, который надо где-то потом unlock» — это устраняет текущий R2-hazard.
4. **Bounded lifecycle.** `shutdownResult` никогда не остаётся незавершённым; `forceFlush()` всегда завершает `CompletableResultCode` (success/fail) детерминированно.
5. **JMX как adapter.** Внутри — `MetricsSnapshot`; наружу для операторов — JMX-атрибуты через `PlatformExportControl`.

### Топ-5 рисков
1. Регрессия фундаментальной семантики drop-oldest при переносе очереди в `ExportBuffer` (R-DROP).
2. Утечка lock-ownership через границу класс `ExportBuffer` ↔ `ExportWorker` → deadlock/double-unlock (R2, целевой дизайн обязан его устранить).
3. `forceFlush + shutdown` гонка → незавершённый promise (R3) — должна быть закрыта новой lifecycle-моделью, а не оставлена.
4. `exporter.shutdown()` без bounded timeout → зависание (R1) — закладываем bounded semantics сразу.
5. Перф-регрессия `onEnd` / producer hot path после реструктуризации.

### Топ non-negotiable инвариантов (см. §4)
drop-oldest eviction под lock; non-blocking onEnd; exporter вне lock; idempotent + bounded shutdown; forceFlush completion guarantee; отсутствие deadlock; opt-in gating; BSP config keys; multi-exporter fallback.

---

## 2. Evidence Base

**Authoritative (репозиторные факты):**
- Исходник: `platform-tracing-otel-extension/.../processor/PlatformDropOldestExportSpanProcessor.java`.
- Досье: `00-executive-summary.md` … `07-llm-research-input.md` (в `docs/refactoring/platform-drop-oldest-export-span-processor/`).
- Смежный код: `PlatformExportProcessorFactory.java`, `PlatformExportControl.java`, `PlatformTracingJmxRegistrar.java`, `DropOldestExportProcessorDefaults.java`, `OtelSdkDefaults.java`, `ExtensionDefaults.java`.
- Тесты: `PlatformDropOldestExportSpanProcessorLifecycleTest`, `...OverflowPolicyTest`, `...QueueCharacterizationTest`, `...BuilderValidationTest`, `BspDropOldestNoDoubleExportTest`, `PlatformAutoConfigurationCustomizerExportProcessorTest`, `SharedDefaultsAlignmentTest`, `DropOldestAspirationDiagnosticsTest`, e2e `BspDropOldestSafetyAgentSmokeTest`.
- Бенчмарки: `QueueOfferBenchmark.java`, `CompositePipelineBenchmark.java`.
- ADR: `ADR-drop-oldest-export-processor-v1.md`, `ADR-bsp-overflow-policy-finding.md`; таксономия `dropped-span-reasons.md`.

**Advisory (multi-model / ensemble review):** DeepResearch v2 (+Summary, +Executive Summary), корректирующие проходы GPT-5.4 и GLM-5.2, Sonar/Kimi/Nemotron/Gemini проходы; архитектурное ревью + системный анализ pre-production breaking-change режима.

**Разделение типов утверждений:**
- *Репозиторные факты* — источник истины (API, locking, счётчики, wiring).
- *Инференции моделей* — подтверждение, что stock BSP = drop-new (SDK 1.61/1.62); что V7/V8/virtual threads не дают чистой выгоды.
- *Рекомендации* — фазовый план, целевая архитектура (числовые scores моделей — decision aid, НЕ доказательство).

**Пробелы доказательной базы:**
- Нет эмпирического процента «допустимой» регрессии — используем «no material regression vs captured baseline».
- Нет production-метрик частоты worker-death / shutdown-hang — закрываем их корректной lifecycle-моделью, а не «по факту».

---

## 3. Current Design Diagnosis

- **Смешанные обязанности (9):** bounded drop-oldest queue; export worker lifecycle; timed batch export; forceFlush coordination; shutdown coordination (отдельный terminator-поток); builder + config validation; чтение BSP config-ключей; observability counters + JMX-геттеры; lifecycle state guard.
- **Почему трудно сопровождать:** ~575 строк без внутренних границ; три потока (producer, worker daemon, terminator daemon) + callback exporter'а; один `queueLock` обслуживает и `queue`, и `pendingFlushes`.
- **Concurrency/lifecycle сложность:** worker-loop с `awaitNanos`; ручной `unlock()` в ветке `InterruptedException` + `isHeldByCurrentThread()` в `finally` (R2).
- **Shutdown-риск:** `exporter.shutdown()` без таймаута (R1); terminator завершает `shutdownResult` по `whenComplete`.
- **forceFlush-риск:** promise в `pendingFlushes` может не завершиться при гонке со shutdown (R3).
- **Observability/JMX coupling:** шесть геттеров — JMX wire-contract через `PlatformExportControl`; `logExportFailureOnce` — постоянный one-shot throttle.
- **Builder/config coupling:** `Builder` (public static final) используется фабрикой; `readBspConfigFrom` обязан читать `otel.bsp.*`; политика «WARN + fallback», единственный fail-fast — null exporter.
- **Factory/rollback coupling:** `PlatformExportProcessorFactory` — единственная prod-фабрика; opt-in / multi-exporter fallback / rollback через снятие env-var.

**Вывод:** при pre-production режиме оптимально не консервировать формы, а перестроить внутренние границы вокруг state machine и явного lock-ownership.

---

## 4. Non-Negotiable Invariants

Даже при breaking changes нельзя ломать фундаментальную семантику процессора.

| Инвариант | Текущий механизм | Потребитель / причина | Существующий тест | Статус в V6-clean |
|---|---|---|---|---|
| Drop-oldest eviction под lock | `pollFirst` затем `offerLast`; `droppedSpansOverflow++` атомарно | Контракт платформы, SRE-таксономия | OverflowPolicyTest, QueueChar T1/T3 | Переносится в `ExportBuffer`, семантика сохраняется |
| Non-blocking `onEnd` | `exporter.export()` вне lock | Не блокировать приложение | QueueChar T2 | Сохраняется (facade → buffer.accept без export под lock) |
| Exporter вне queue lock | export в worker после unlock | non-blocking гарантия | T2 (косвенно) | Гарантируется: worker получает `ExportWork` с уже отпущенным lock |
| `forceFlush` после shutdown → success | ранний `ofSuccess()` | OTel multi-lang контракт | — | Сохраняется как явная policy в `ProcessorLifecycle` |
| Идемпотентность `shutdown` | `compareAndSet` + кэш `shutdownResult` | OTel SDK | Lifecycle idempotent | Сохраняется в `ProcessorLifecycle` |
| **Bounded shutdown** | (отсутствует — R1) | надёжность shutdown | — | **НОВЫЙ инвариант**: bounded timeout, никогда не incomplete |
| **forceFlush completion guarantee** | (частично нарушен — R3) | lifecycle корректность | — | **НОВЫЙ инвариант**: всегда success/fail, никогда incomplete |
| Отсутствие deadlock / lock-leak | ручной unlock (R2) | корректность | — | Устраняется: lock не пересекает границы классов |
| BSP config keys | `readBspConfigFrom` (`otel.bsp.*`) | паритет с stock BSP | SharedDefaultsAlignment | Переносится в `DropOldestExportProcessorPropertiesReader` |
| Opt-in gating | `isExplicitUpstream` в фабрике | rollback | AutoConfigCustomizer | Сохраняется (factory не ломаем по смыслу) |
| Multi-exporter fallback | fallback на stock BSP | надёжность | AutoConfigCustomizer | Сохраняется |
| `exportTimeouts ⊆ exportFailures` | оба инкремента при timeout | observability | Lifecycle timeout | Переносится в `TimedSpanExporter`/metrics |

**Переведено из hard constraints в migration decisions (см. §10):**
- Public `Builder` API — можно заменить на config-first builder (factory wiring обновляется в том же PR).
- 6 индивидуальных JMX getters (internal API) — заменяются на `MetricsSnapshot`; **JMX сохраняется как adapter-слой** (атрибуты читаются из snapshot).
- Текущая shutdown semantics (unbounded) — заменяется на bounded.
- One-shot `logExportFailureOnce` — может стать rate-limited / structured logging.

**JMX schema — может быть переработана (имена НЕ являются constraint):**
> JMX capability must be preserved, but the operator-facing JMX schema may be redesigned.
>
> Internal processor observability is based on `DropOldestProcessorMetricsSnapshot`. `PlatformExportControl` acts as an adapter from `MetricsSnapshot` to JMX attributes.
>
> The JMX attribute names should be derived from the new snapshot model and documented as the new operator contract. Backward compatibility with the old six getter-derived JMX attribute names is **not** required in pre-production mode.

**Что в JMX остаётся non-negotiable (смысл наблюдаемости, а не имена):**
- сам факт наличия JMX-доступа для операторов;
- смысл ключевых counters;
- возможность увидеть queue size / capacity;
- возможность отличить overflow drops от after-shutdown drops;
- возможность видеть export failures / timeouts;
- документированный mapping `MetricsSnapshot` → JMX attributes.

**Принцип:** `MetricsSnapshot` — новая source-of-truth observability model; JMX — adapter над snapshot; JMX schema может быть новой и **должна быть явно задокументирована**. Старые имена — можно ломать; смысл наблюдаемости — нельзя терять.

---

## 5. Characterization Tests (Phase 0 / PR-0) — safety net, обязательны

Минимально обязательные тесты (фиксируют ключевые инварианты и known failures):

| Тест | Покрываемый инвариант/риск | Детерминизм | Fake/double | Ожидаемый результат |
|---|---|---|---|---|
| M1 concurrent `forceFlush + shutdown` | R3 (primary) | `CountDownLatch`/`CyclicBarrier`, bounded-timeout assert | `BlockingExporter` | Ни один promise не остаётся incomplete |
| M2 multiple concurrent `forceFlush` | `pendingFlushes` batching / lock-coupling (НЕ primary для R3) | `CyclicBarrier` старт N потоков | `CountingExporter` | Все N promise завершены |
| M3 exporter.shutdown() hang | R1 | exporter с незавершающимся shutdown + bounded assert | `NeverCompleteShutdownExporter` | Документирует текущее зависание (known failure / @Disabled+ref). **НЕ вызывать `shutdown().join()` без bounded timeout** |
| M4a exporter exception isolation | exporter throw | инъекция throw по счётчику | `ThrowingExporter(n)` | `exportFailures++`, worker продолжает работу |
| M4b unexpected worker-loop death (white-box, опц.) | R4 | fault-injection в worker-loop (НЕ через `exporter.export`) | white-box hook | Если hook невозможен без prod-изменений — analysis-only |
| M5 детерминированный `scheduleDelay` export | обсервабилити | короткий delay + ожидание | `CountingExporter` | Background-flush без forceFlush |
| M6 shutdown-timeout interrupt path (integration) | R2 (косвенно) | короткий `shutdownTimeout` → worker прерывается | `NeverCompletingResultCodeExporter` | `shutdown` завершается; `droppedAfterShutdown>0`. Это integration characterization, НЕ точная проверка строки `awaitNanos` |
| M7 `forceFlush` после завершённого shutdown | forceFlush-after-shutdown | вызвать после `shutdown().join(timeout)` | `CountingExporter` | `ofSuccess()` немедленно |
| M8 `toSpanData()` RuntimeException | потеря span в onEnd | stub бросает | стаб span | span потерян тихо; процессор продолжает |
| M9 log throttling | политика throttle | SLF4J test-appender | `ThrowingExporter` | Текущая политика зафиксирована (one WARN на серию) |
| M10 drop-oldest preserves newest + non-blocking onEnd + exporter outside lock | фундаментальные инварианты | barrier + cap=N | `BlockingExporter` | newest сохранён, onEnd не блокирует, export вне lock |
| M11 JMH/stress baseline | перф-регрессия | `QueueOfferBenchmark` по процедуре §11 | Noop/Stuck exporter | Зафиксированный baseline (артефакт) |

### Phase 0 Failure Decision Policy
- Если **M1/M2** показывают зависший `CompletableResultCode` — это фиксируется как **known failure** (issue/ADR ref). В V6-clean он будет закрыт в PR-3 (clean lifecycle). PR-1/PR-2 могут стартовать параллельно, но **PR-3 обязан сделать M1/M2 зелёными**.
- Если **M6** нестабилен — трактуем как integration characterization сигнал; не «лечим» ослаблением assert, заводим issue.
- **Запрещено** делать падающий характеризационный тест зелёным ослаблением assertions или изменением production-поведения.
- Различаем **active** и **disabled** тесты: активный проверяет текущее поведение без зависания, либо `@Disabled` со ссылкой на issue/ADR.

---

## 6. Refactoring Strategy

> Миграция **test-first** и **component-first**: сначала safety net и скелет компонентов с unit-тестами, затем перенос логики и facade, затем clean lifecycle semantics, затем замена API/JMX-adapter и удаление legacy, в конце — перф/стресс-валидация.

### PR-0 — Characterization Tests + Baseline (safety net)
- Только тесты и бенчмарки. Ноль production-изменений.
- Зафиксировать ключевые инварианты (§5) и known failures.
- **Acceptance (строго):** все существующие тесты зелёные; активные новые тесты зелёные; known failures либо `@Disabled` со ссылкой, либо активны без зависания; JMH baseline сохранён как артефакт; production-код не изменён; зелёный прогон не достигнут ослаблением assertions.

### PR-1 — Minimal Components (НЕ весь каталог)
- Создать **только минимальный набор** низкорисковых компонентов с unit-тестами: `DropOldestExportProcessorConfig`, `TimedSpanExporter`, `DropOldestProcessorMetrics` + `DropOldestProcessorMetricsSnapshot`.
- `ExportBuffer`, `ExportWorker`, `ProcessorLifecycle` **НЕ создаются в PR-1** — они вводятся в PR-2 вместе с реальным переносом логики (меньше риска speculative scaffolding).
- Цель PR-1 — границы и unit-tests, а не поведение целиком (особенно `TimedSpanExporter` классификация и `DropOldestProcessorMetricsSnapshot` immutability).
- **No fake production behavior:** компоненты — package-private протестированные units; никаких псевдо-реализаций и **никакого partial wiring в production path** без полного тестового покрытия. Соблюдать §7a.
- **PR-1 НЕ меняет `PlatformExportProcessorFactory` wiring.** `DropOldestExportProcessorConfig` вводится как internal протестированный unit; production wiring меняется только в PR-4.

### PR-2 — Buffer + Worker + Lifecycle (перенос логики, thin facade)
- Ввести `ExportBuffer`, `ExportWorker`, `ProcessorLifecycle` и перенести в них реальную логику; `PlatformDropOldestExportSpanProcessor` становится thin facade:
  - `onEnd` → `lifecycle.accept(spanData)` (через `ExportBuffer.accept`, non-blocking);
  - `forceFlush` → `lifecycle.forceFlush()`;
  - `shutdown` → `lifecycle.shutdown()`;
  - метрики → `metrics.snapshot()`.
- **Legacy getters (мягко):** facade в PR-2 **может временно сохранить** старые getter-методы, делегирующие в `metrics.snapshot()`, чтобы не ломать тесты до API-миграции; их удаление/замена — в PR-4 вместе с JMX/factory/docs. PR-2 не должен ломать API раньше PR-4.
- `ExportBuffer` владеет queue + pendingFlushes + lock + condition; `ExportWorker.awaitWork()` получает готовый `ExportWork`.
- **Acceptance:** все характеризационные тесты PR-0 (кроме явных known failures R1/R3, закрываемых в PR-3) зелёные; фундаментальные инварианты (M10) зелёные.
- **Merge rule (PR-2 ↔ PR-3 — одна архитектурная связка):** PR-2 может временно сохранять старые lifecycle known-failures (R1/R3) **только если** PR-3 выполняется сразу за ним и обе PR ревьюятся как единый архитектурный batch. Не допускать ситуации, где новая структура (PR-2) надолго живёт со старыми lifecycle-дефектами.

### PR-3 — Clean Shutdown / ForceFlush Semantics
Встроить корректную целевую lifecycle-модель (не hotfix):

```text
shutdown():
- idempotent;
- requests terminal lifecycle state;
- wakes worker;
- drains queue up to shutdown timeout;
- calls exporter.shutdown with bounded timeout;
- returns failed CompletableResultCode on timeout/failure;
- never leaves shutdownResult incomplete.

forceFlush():
- if RUNNING: enqueue flush request, complete after export attempt;
- if SHUTTING_DOWN: include in shutdown drain; fail deterministically ONLY when it cannot be included in the drain;
- if TERMINATED: return CompletableResultCode.ofSuccess() (no pending work; preserves current safe post-shutdown behavior);
- never returns a CompletableResultCode that can remain incomplete forever.
```

- Главный clean lifecycle invariant: **никогда не оставлять `CompletableResultCode` незавершённым.**
- Bounded shutdown timeout: `shutdownResult` завершается как fail **ровно один раз** при timeout, WARN со значением таймаута и классом exporter'а; не экспортировать дополнительное состояние после старта фазы shutdown exporter'а; не репортить success без явной SRE-политики «best-effort shutdown».
- **forceFlush after TERMINATED → `ofSuccess()`** (нет pending work; сохраняет текущее безопасное post-shutdown поведение и согласуется с инвариантом «forceFlush после shutdown → success»).
- **forceFlush during SHUTTING_DOWN** — включается в shutdown drain; **fail только** если не может быть включён в drain.
- forceFlush pending in-flight, который не может быть завершён экспортом при переходе в terminal — завершать как **fail**, не оставлять incomplete.
- **Acceptance:** M1, M2, M3, M6 зелёные; bounded shutdown и deterministic forceFlush подтверждены тестами.

### PR-4 — Replace Old API / JMX / Builder
Breaking changes допустимы (pre-production):
- заменить `Builder` на config-first builder (`DropOldestExportProcessorConfigBuilder`);
- заменить 6 индивидуальных getters на `DropOldestProcessorMetricsSnapshot`;
- **JMX как adapter над `MetricsSnapshot`:** `PlatformExportControl` читает поля snapshot и отдаёт их как JMX-атрибуты (для операторов); JMX schema **перепроектируется** из snapshot-модели и документируется как новый operator contract; backward compatibility со старой JMX schema **не требуется**;
- обновить `PlatformExportProcessorFactory` (wiring) **в том же PR**;
- обновить docs (включая mapping `MetricsSnapshot` → JMX attributes).
- **Acceptance:** factory компилируется и работает; JMX-атрибуты доступны операторам через adapter над `MetricsSnapshot`; новая JMX schema задокументирована; имена атрибутов соответствуют новой snapshot-модели; сохранены смысл counters, queue size/capacity, разделение overflow vs after-shutdown drops, export failures/timeouts; opt-in/rollback/multi-exporter fallback не сломаны.

### PR-5 — Remove Legacy / Transitional Debris
- Удалить временные adapters, deprecated методы, transitional мост из PR-1/PR-2, старую документацию.
- **Acceptance:** нет мёртвого/переходного кода; все тесты зелёные.

### PR-6 — JMH + Stress Validation
- Прогнать benchmark/stress по процедуре §11 после новой архитектуры; сравнить с baseline PR-0.
- **Acceptance:** no material regression vs baseline; stress без deadlock/lock-leak/incomplete promise.

---

## 7. Proposed Package and Class Layout (V6-clean target)

> **V6-clean-lite:** только реальные архитектурные границы, убирающие сложность. Никакого «mini-framework» из 18+ классов — целевой объём ~7–8 production-классов, которые ревьюер может понять за один проход. Speculative abstractions (strategies, runtimes, classifiers, services, отдельные coordinators/loggers) НЕ создаются, пока их не потребует код (см. §7a Anti-Overengineering Rules).
>
> **Счёт классов:** nested records/enums и существующий JMX adapter (`PlatformExportControl`) не считаются отдельными standalone-компонентами архитектуры.

```text
space.br1440.platform.tracing.otel.extension.processor
  PlatformDropOldestExportSpanProcessor   // thin facade, implements SpanProcessor

space.br1440.platform.tracing.otel.extension.processor.internal
  DropOldestExportProcessorConfig         // immutable config + validation + otel.bsp.* reading
  ExportBuffer                            // owns ReentrantLock + Condition + ArrayDeque + pending flushes + drop-oldest accounting
  ExportWorker                            // owns worker thread; awaitWork() returns ready ExportWork (lock released)
  TimedSpanExporter                       // export(batch) + timeout + failure classification (private methods)
  ProcessorLifecycle                      // owns RUNNING/SHUTTING_DOWN/TERMINATED + shutdownResult + forceFlush/shutdown policy
  DropOldestProcessorMetrics              // owns counters; observability source-of-truth
  DropOldestProcessorMetricsSnapshot      // immutable snapshot

  // JMX adapter — PlatformExportControl (существующий) читает MetricsSnapshot
```

**Внутренняя структура (nested records / private methods вместо top-level классов):**

```text
ExportBuffer
  - owns ReentrantLock + Condition
  - owns ArrayDeque<SpanData>
  - owns pending flush requests
  - owns drop-oldest accounting
  - nested record ExportWork(...)      // бывш. BufferDrainResult
  - nested record FlushRequest(...)

TimedSpanExporter
  - export(batch)
  - timeout handling
  - failure classification as PRIVATE methods   // бывш. ExportFailureClassifier
  - nested record ExportResult(...)             // бывш. ExportAttemptResult

ProcessorLifecycle
  - owns RUNNING / SHUTTING_DOWN / TERMINATED
  - owns shutdownResult
  - owns forceFlush/shutdown policy
  - private shutdown coordination methods        // ShutdownCoordinator выносится ТОЛЬКО если разрастётся

ExportWorker
  - owns worker thread
  - execution state as nested enum (НЕ отдельный ExportWorkerState класс)
```

**Понижены / убраны из target layout** (см. §10): `ExportBatch`, `FlushRequest`, `BufferDrainResult` → nested records; `DropOldestOverflowStrategy` → убран (политика одна); `ExportWorkerState` → nested enum; `ExportWorkerRuntime` → убран; `ExportAttemptResult` → nested record; `ExportFailureClassifier` → private methods; `ShutdownCoordinator` → private collaborator внутри `ProcessorLifecycle` (выносить только при росте); `ForceFlushService` → убран (часть `ProcessorLifecycle` + `ExportBuffer`); `DropOldestProcessorLogger` → private logging/rate-limit внутри metrics/exporter/lifecycle; `DropOldestExportProcessorConfigBuilder`/`DropOldestExportProcessorPropertiesReader` → внутри `DropOldestExportProcessorConfig`.

### JMX schema (рекомендуемая, перепроектированная из snapshot)

`MetricsSnapshot` — source-of-truth; JMX-атрибуты прямо отображают поля snapshot (не старые getters). Рекомендуемый набор атрибутов:

```text
DroppedSpansOverflow
DroppedSpansAfterShutdown
ExportFailures
ExportTimeouts
QueueCapacity
QueueSize
LifecycleState
WorkerState
LastExportFailureReason
LastExportFailureTimestamp
LastSuccessfulExportTimestamp
```

Первые 6 могут остаться похожими на старые (они удачные и понятны операторам) — но это **осознанный дизайн нового adapter'а**, а не backward-compatibility obligation.

Mapping (JMX getter методы остаются внутри MBean ради JMX conventions, но processor больше не обязан иметь старые 6 getters):

```text
DropOldestProcessorMetricsSnapshot snapshot = metrics.snapshot();

jmx.getDroppedSpansOverflow()      -> snapshot.droppedSpansOverflow()
jmx.getDroppedSpansAfterShutdown() -> snapshot.droppedSpansAfterShutdown()
jmx.getExportFailures()            -> snapshot.exportFailures()
jmx.getExportTimeouts()            -> snapshot.exportTimeouts()
// ... остальные атрибуты аналогично из полей snapshot
```

### Целевая ответственность компонентов

```text
PlatformDropOldestExportSpanProcessor  — thin SpanProcessor facade; no queue/worker/shutdown/timeout/builder logic
ProcessorLifecycle                     — state machine; owns shutdownResult; forceFlush/shutdown policy + private shutdown coordination; no incomplete CompletableResultCode
ExportBuffer                           — ReentrantLock+Condition; ArrayDeque<SpanData>; pending flush requests; drop-oldest atomic accounting; returns ExportWork with lock released
ExportWorker                           — worker thread; buffer.awaitWork(); timedExporter.export(); completes flush requests; one clear terminal exit path
TimedSpanExporter                      — exporter.export(batch); export timeout; classify success/failure/timeout (private); never owns queue/lifecycle state
DropOldestExportProcessorConfig        — immutable config + validation + otel.bsp.* reading
DropOldestProcessorMetrics             — counters; immutable snapshot (MetricsSnapshot); JMX adapter reads snapshot
```

**Главный архитектурный принцип:** lock ownership не пересекает границы классов неявно. `ExportWorker` вызывает `ExportBuffer.awaitWork()` и получает уже готовый `ExportWork`, а не «lock, который надо потом где-то unlock». Это устраняет текущий R2-hazard на уровне дизайна.

**Единственный владелец состояния processor (no duplicate state machines):**
- `ProcessorLifecycle` **владеет** lifecycle-состоянием processor (`RUNNING` / `SHUTTING_DOWN` / `TERMINATED`) и публичными операциями: `accept`, `forceFlush`, `shutdown`. Shutdown-координация — **private методы** внутри `ProcessorLifecycle`; отдельный `ShutdownCoordinator` выносится ТОЛЬКО если класс разрастётся (см. §7a).
- Execution-состояние worker thread — **nested enum внутри `ExportWorker`**; оно **не** решает, в каком состоянии (`RUNNING/SHUTTING_DOWN/TERMINATED`) находится весь processor. Это исключает появление второй lifecycle.

| Класс | Обязанность | Видимость | Ключевой инвариант |
|---|---|---|---|
| `PlatformDropOldestExportSpanProcessor` | facade | public | только делегирование |
| `ProcessorLifecycle` | state machine + shutdownResult + shutdown coord (private) | package-private | нет incomplete CompletableResultCode |
| `ExportBuffer` | queue+pendingFlushes+lock (+ nested `ExportWork`/`FlushRequest`) | package-private | drop-oldest атомарно; lock не утекает |
| `ExportWorker` | worker thread (+ nested state enum) | package-private | один terminal exit path |
| `TimedSpanExporter` | export+timeout+classify (private) (+ nested `ExportResult`) | package-private | синхронен с точки зрения worker'а; без нового executor/thread |
| `DropOldestExportProcessorConfig` | config + validation + bsp keys | package-private | immutable; null-exporter fail-fast |
| `DropOldestProcessorMetrics` | counters+snapshot | package-private | immutable snapshot; JMX читает snapshot |

---

## 7a. Anti-Overengineering Rules

> Цель — компонентность, а не mini-framework внутри одного processor'а. Для этой задачи достаточно ~7–8 production-классов, а не 18+.

1. No interface unless there are at least two real implementations or a test seam cannot be achieved otherwise.
2. No Strategy pattern for a single fixed policy. Drop-oldest is the only supported overflow policy inside this processor.
3. No top-level value object if it is used by only one component; prefer private/nested record.
4. No separate Service/Coordinator/Runtime class unless it owns a clearly independent lifecycle or state.
5. No extra executor/thread inside `TimedSpanExporter`.
6. No abstraction over `ReentrantLock`/`Condition` unless it removes lock ownership risk.
7. No "skeleton" classes with speculative responsibilities.
8. Every new class must remove at least one responsibility from the old processor and have direct tests.
9. Prefer package-private final classes and records.
10. Keep the number of production classes small enough that a reviewer can understand the whole processor in one pass.

---

## 8. Detailed PR Plan

### PR-0 — Characterization Tests & Benchmark Baseline (safety net)
- Добавить тесты §5 (M1, M2, M3, M4a, опц. M4b, M5, M6, M7, M8, M9, M10) в `platform-tracing-otel-extension/src/test/.../processor/`.
- Добавить test doubles: `NeverCompleteShutdownExporter`, `SlowExporter`, `InterruptTrackingExporter`; переиспользовать `BlockingExporter`/`CountingExporter`.
- Зафиксировать JMH baseline (M11) по `QueueOfferBenchmark` по процедуре §11; сохранить артефакт.
- **Acceptance:** строгие merge criteria (active vs disabled; без ослабления assert); Phase 0 Failure Decision Policy для M1/M2/M6.
- **Rollback:** revert PR (только тесты).

### PR-1 — Minimal Components
- Создать **только** `DropOldestExportProcessorConfig`, `TimedSpanExporter`, `DropOldestProcessorMetrics` + `DropOldestProcessorMetricsSnapshot` + unit-тесты. `ExportBuffer`/`ExportWorker`/`ProcessorLifecycle` — в PR-2.
- **Compile-safe, без fake behavior:** компоненты — package-private протестированные units; нет partial wiring в production path без полного покрытия; соблюдать §7a (никаких speculative strategies/runtimes/classifiers/services).
- **НЕ менять `PlatformExportProcessorFactory` wiring** — config вводится как internal unit, production wiring обновляется в PR-4.
- **Acceptance:** unit-тесты компонентов зелёные; существующие тесты не сломаны; factory не тронут; нет псевдо-реализаций; число новых классов минимально.
- **Rollback:** revert PR.

### PR-2 — Buffer + Worker + Lifecycle
- Ввести `ExportBuffer`, `ExportWorker`, `ProcessorLifecycle`; перенести логику; facade становится тонким; `ExportBuffer` владеет lock; `ExportWorker.awaitWork()` отдаёт готовый `ExportWork`. Вспомогательные типы — nested records/enum (§7), не top-level.
- **Legacy getters:** facade может временно оставить старые getter-методы (делегируют в `metrics.snapshot()`); их замена/удаление — в PR-4. PR-2 не ломает публичный API раньше API-миграции.
- **Acceptance:** характеризационные тесты PR-0 зелёные (кроме known failures R1/R3 → PR-3); M10 зелёный; JMH без материальной регрессии vs baseline.
- **Rollback:** revert PR.

### PR-3 — Clean Shutdown / ForceFlush Semantics
- Реализовать bounded shutdown + deterministic forceFlush (§6 PR-3). Обновить ADR.
- **Acceptance:** M1, M2, M3, M6 зелёные; нет incomplete CompletableResultCode; semantic change задокументирован в ADR.
- **Rollback:** revert PR (поведение возвращается к pre-clean-lifecycle).

### PR-4 — Replace Old API / JMX / Builder
- config-first builder; snapshot вместо 6 getters; JMX adapter (`PlatformExportControl` читает snapshot) с **перепроектированной schema** из snapshot-модели; обновить `PlatformExportProcessorFactory` в том же PR; docs (+ mapping `MetricsSnapshot` → JMX attributes).
- **Acceptance:** factory работает; JMX-атрибуты доступны через adapter над `MetricsSnapshot`; новая JMX schema задокументирована, имена соответствуют snapshot-модели, backward compat со старой schema не требуется; смысл наблюдаемости (counters, queue size/capacity, overflow vs after-shutdown, failures/timeouts) сохранён; opt-in/rollback/fallback не сломаны.
- **Rollback:** revert PR.

### PR-5 — Remove Legacy / Transitional Debris
- Удалить мост, deprecated, transitional adapters, старую документацию.
- **Acceptance:** нет мёртвого кода; все тесты зелёные.
- **Rollback:** revert PR.

### PR-6 — JMH + Stress Validation
- Перф/стресс по §11; публикация результатов vs baseline.
- **Acceptance:** no material regression; stress без deadlock/lock-leak/incomplete promise.
- **Rollback:** не структурный (валидация).

Рисковые фазы (PR-2, PR-3) не объединяются в один PR.

---

## 9. Risk Register

| Риск | Severity | Probability | Detection | Mitigation | Phase |
|---|---|---|---|---|---|
| Регрессия drop-oldest (R-DROP) | High | Medium | OverflowPolicy/QueueChar/M10 | eviction+counter атомарно в `ExportBuffer` | PR-2 |
| Утечка lock-ownership через границу классов (R2) | High | Medium | concurrency-тесты, M6, stress | `awaitWork()` отдаёт готовый `ExportWork`; нет ручного unlock | PR-1/PR-2 |
| `forceFlush` promise incomplete (R3) | High | Medium | M1/M2 | clean lifecycle, completion guarantee | PR-3 |
| `shutdownResult` incomplete / hang (R1) | High | Medium | M3, stress | bounded shutdown, exactly-once completion | PR-3 |
| Worker death (R4) | Medium | Low-Med | M4a/M4b | один terminal exit path; изоляция exporter exception | PR-2/PR-3 |
| Перф-регрессия `onEnd`/hot path | Medium | Medium | JMH vs baseline | gate «no material regression» | PR-2/PR-6 |
| Слом opt-in/rollback/fallback | High | Low | AutoConfig тесты | не менять смысл factory gating | PR-4 |
| Операторы теряют JMX-наблюдаемость | Medium | Low | JMX adapter тест | adapter над snapshot сохраняет capability и смысл counters; новая schema документирована (имена могут меняться) | PR-4 |
| Незаметная смена логирования | Medium | Low | M9 | задокументировать переход на rate-limited/structured | PR-3/PR-4 |

---

## 10. Rejected / Migration Decisions

### Остаются Reject (актуальны и при breaking changes)
| Альтернатива | Вердикт | Причина |
|---|---|---|
| Замена на stock BSP | Reject | stock BSP = drop-new (SDK 1.61/1.62); ломает требование drop-oldest |
| Disruptor / ring buffer | Reject | новая модель и сложность; нет overwhelming перф-обоснования |
| Virtual threads как «решение архитектуры» | Reject | не решают bounded-queue semantics, forceFlush/shutdown-координацию, shutdown state machine и producer hot path; один long-lived worker не bottleneck |
| Механическая декомпозиция без state machine | Reject | разнести классы «как есть» сохранит текущие hazard'ы; нужна редизайн-граница |
| V6-clean full decomposition (18+ классов, strategies/runtimes/classifiers/services/coordinators) | Reject → V6-clean-lite | overengineering для одного processor'а; speculative abstractions; используем nested records/private methods (§7a), ~7–8 production-классов |
| Strategy pattern для overflow policy | Reject | drop-oldest — единственная поддерживаемая политика; Strategy преждевременен |
| Отдельные `ShutdownCoordinator`/`ForceFlushService`/`DropOldestProcessorLogger` сразу | Postpone | сначала private collaborator/methods; выносить только при доказанном росте |
| `ExportBuffer` отдельно от `pendingFlushes` | Reject | queue и pending flushes под одним lock — буфер обязан владеть обоими |
| Независимая forceFlush-экстракция вне lock | Reject | та же связка queue+pendingFlushes; решается внутри `ExportBuffer`/`ProcessorLifecycle` |
| `TimedSpanExporter` через отдельный executor/thread | Reject | меняет threading-модель; должен быть синхронен с точки зрения worker'а (`export(batch).join(timeout)`) |
| Оставить `shutdownResult` потенциально вечным | Reject | нарушает clean lifecycle invariant |
| Ручной `unlock()` вне нормального lock-ownership protocol | Reject | сохраняет R2-hazard |

### Переведено из hard constraints в Migration Decisions (pre-production)
| Область | Было | Стало в V6-clean |
|---|---|---|
| Public Builder API | заморожен | config-first builder; factory обновляется в том же PR (PR-4) |
| 6 JMX getters / JMX schema | заморожены | `MetricsSnapshot` — source-of-truth; **JMX сохранён как adapter**, schema перепроектирована из snapshot и документируется как новый operator contract; старые attribute names — не constraint (capability и смысл наблюдаемости остаются) |
| V6 full decomposition | postpone | **основная target architecture** |
| Shutdown semantics | менять только через ADR | bounded semantics закладывается сразу (PR-3, с ADR) |
| Logging throttle | one-shot заморожен | можно перейти на rate-limited / structured logging |
| Queue/worker extraction | только после Phase 0–2 | центральная архитектурная фаза (PR-1/PR-2) |
| ForceFlush behavior | сохранить компромиссы | чистый строгий lifecycle contract (PR-3) |

---

## 11. Benchmark Plan

> Критерий везде: «no material regression vs captured baseline» (baseline из PR-0).

**Процедура сравнения (обязательна):**
- Одинаковая среда: та же машина/CI-runner, тот же JDK (версия + vendor), те же JVM-flags.
- Одинаковые параметры JMH: фиксированное число forks, warmup и measurement iterations.
- Сохранять baseline как артефакт (raw JMH output + сводка) в репозитории/CI.
- Сравнение «до/после» на одинаковой конфигурации; учитывать CI-noise (error/доверительный интервал JMH).
- При статистически заметном ухудшении — human review и решение architects до мерджа структурной фазы.

| Benchmark | Назначение | Setup | Метрика | Merge criterion |
|---|---|---|---|---|
| steady `onEnd` | типичный enqueue | `QueueOfferBenchmark.dropOldestOfferSteady`, NoopExporter | avgt ns/op | no material regression |
| saturated eviction | ветка drop-oldest | `dropOldestOfferSaturatedEviction`, StuckExporter, cap=32 | avgt + p99 | no material regression |
| exporter blocked | non-blocking onEnd под блокировкой | BlockingExporter, multi-producer | latency onEnd | onEnd не блокирует |
| multi-producer contention | контеншн на буфере | `@Threads(4)` | avgt/p99 | no material regression |
| forceFlush pressure | стоимость flush-координации | периодический forceFlush | avgt | no material regression |
| shutdown drain | дренаж при shutdown | очередь N, затем close | время дренажа | завершение в пределах shutdownTimeout |
| stress (PR-6) | deadlock/lock-leak/incomplete promise | смешанная нагрузка onEnd+forceFlush+shutdown | отсутствие hang | нет incomplete CompletableResultCode |

---

## 12. ADR Decision Draft

**# ADR: Clean-architecture рефакторинг `PlatformDropOldestExportSpanProcessor` (V6-clean)**

**## Status** — Proposed

**## Context** — Класс production-critical по семантике (opt-in `DROP_OLDEST`), но **ещё не вышел в production**, breaking changes допустимы. ~575 строк, 9 обязанностей, сложная concurrency (3 потока, общий lock для queue+pendingFlushes). Подтверждённые риски: R1 (shutdown без таймаута), R2 (ручной unlock), R3 (forceFlush promise loss), R4 (worker death). Stock BSP = drop-new и не может заменить класс.

**## Decision** — Adopt **V6-clean-lite** architecture:
- thin SpanProcessor facade;
- `ExportBuffer` owning queue, pending flushes, lock, condition;
- `ExportWorker` owning the worker thread (получает готовый `ExportWork`, lock не утекает);
- `ProcessorLifecycle` owning processor state and public lifecycle operations (shutdown coordination — private методы);
- `TimedSpanExporter` owning export timeout/failure handling;
- `DropOldestExportProcessorConfig` owning config/validation/bsp keys;
- `DropOldestProcessorMetricsSnapshot` as observability source-of-truth;
- JMX adapter over the snapshot.

Avoid speculative subcomponents such as standalone strategies, classifiers, runtimes, services, or coordinators until code size and tests prove they are needed (см. §7a). Builder → config-first builder; 6 getters → snapshot; JMX schema перепроектирована из snapshot. Миграция test-first/component-first: PR-0 (safety net) → PR-1 (минимальные компоненты) → PR-2 (buffer/worker/lifecycle + facade) → PR-3 (clean lifecycle) → PR-4 (API/JMX adapter/factory) → PR-5 (remove legacy) → PR-6 (JMH/stress).

**## Non-Goals** — Замена на stock BSP; Disruptor/ring buffer; virtual threads как архитектурное решение; механическая декомпозиция без state machine; `ExportBuffer` отдельно от pendingFlushes; `TimedSpanExporter` через отдельный executor; оставлять `shutdownResult` вечным; ручной unlock вне lock-ownership protocol.

**## Consequences**
- *Positive:* чистые границы; устранён R2 на уровне дизайна; bounded shutdown и deterministic forceFlush; immutable metrics snapshot; обозримый facade.
- *Negative:* breaking changes (Builder/getters); требуется обновить factory и docs; больший объём изменений, чем incremental.
- *Neutral:* фундаментальная семантика (drop-oldest, non-blocking onEnd, exporter вне lock, opt-in, BSP keys, fallback) сохранена; JMX операторам доступен через adapter.

**## Alternatives Considered** — Conservative incremental refactoring (прежний план) — оптимален для production-safe режима, но отвергнут как слишком консервативный для pre-production; V7/V8/virtual threads — отклонены (см. §10).

**## Rollback** — Каждая фаза = отдельный revertable PR; rollback всего opt-in — снятие `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` (возврат к stock BSP).

**## Test Gates** — Существующие тесты + M1–M11 (PR-0 safety net); M1/M2/M3/M6 обязаны стать зелёными к концу PR-3; JMX adapter тест в PR-4.

**## Benchmark Gates** — «no material regression vs captured baseline» (§11) + stress без incomplete promise/deadlock (PR-6).

---

## 13. Final Implementation Prompt for Composer (PR-0 ONLY)

> Реализуй ТОЛЬКО PR-0 (раздельные commits: PR-0A тесты, PR-0B benchmark). Никакого рефакторинга production-кода. PR-0 — safety net для последующей clean-архитектуры.
>
> Можно:
> - Добавить тесты в `platform-tracing-otel-extension/src/test/java/space/br1440/platform/tracing/otel/extension/processor/`: M1 (concurrent forceFlush+shutdown), M2 (multiple concurrent forceFlush), M3 (exporter.shutdown hang — @Disabled со ссылкой как known-failure), M4a (exporter exception isolation), M5 (детерминированный scheduleDelay export), M6 (shutdown-timeout interrupt path — integration characterization, НЕ точная проверка awaitNanos), M7 (forceFlush после завершённого shutdown), M8 (toSpanData RuntimeException), M9 (log throttling через SLF4J test-appender), M10 (drop-oldest preserves newest + non-blocking onEnd + exporter outside lock).
> - M4b (unexpected worker-loop death) — ТОЛЬКО как white-box/fault-injection; НЕ моделировать через exporter.export() (исключение ловится в exportBatch). Если white-box hook невозможен без prod-изменений — НЕ добавлять.
> - M3 НЕ должен вызывать `shutdown().join()` без bounded timeout (использовать `join(timeout)`/`get(timeout)`).
> - Добавить test doubles: `NeverCompleteShutdownExporter`, `SlowExporter`, `InterruptTrackingExporter`; переиспользовать `BlockingExporter`/`CountingExporter`.
> - Зафиксировать JMH baseline (M11) по `QueueOfferBenchmark` по процедуре §11 и сохранить артефакт.
>
> Нельзя:
> - Менять любой production-класс (`PlatformDropOldestExportSpanProcessor`, фабрику, JMX, конфиг).
> - Менять существующие тесты.
> - Добавлять зависимости.
> - Менять семантику shutdown/forceFlush/логирования.
> - **Делать тест зелёным путём ослабления assertions или изменения production-поведения.** Если M1/M2/M6 реально падают из-за бага — НЕ маскировать: либо `@Disabled` со ссылкой на issue/ADR, либо активный тест, фиксирующий текущее неполное поведение без зависания.
>
> Acceptance: все существующие тесты зелёные; активные новые тесты зелёные; известные баги либо @Disabled со ссылкой, либо активны без зависания; baseline сохранён. Каждый тест — детерминированный (latch/barrier, без sleep-таймингов где возможно).
