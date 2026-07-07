<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## 1. Executive Verdict

- **Можно ли утверждать V6-clean-lite как есть?** Нет. В текущем виде план **не соответствует архитектурному governance**: он завышает уровень уверенности (“target architecture”), включает минимум два явно семантических изменения (`shutdown`, `forceFlush`) без отдельного ADR и опирается на устаревший rollback‑контракт (unset → UPSTREAM), который в репозитории уже не верен.[^1][^2]
- **Что нужно изменить до principal‑аппрува?**
    - Понизить уровень уверенности до **“candidate target architecture, subject to ADR approval and Phase 0–3 validation”**.[^3][^4]
    - Явно оформить **отдельные ADR** для: V6-clean-lite, bounded shutdown, deterministic forceFlush, JMX schema migration и корректного rollback (UPSTREAM, а не “unset”).[^2][^5][^6]
    - Учёт того, что `exporter.shutdown()` без таймаута и гонка `forceFlush + shutdown` — уже наблюдаемое поведение; их изменение — не “рефакторинг”, а **семантический баг‑фикс**, требующий ADR и тест‑гейтов.[^6][^7]
    - Уточнить границы миграции JMX: разнести **Java‑геттеры процессора** и **JMX MBean‑атрибуты** и явно описать миграционный путь для операторов.[^5][^1]
- **Корректное словесное обозначение архитектурной определённости:**
    - Сейчас: **“architecture hypothesis / candidate target architecture”**.
    - После PR‑0 + утверждённого ADR: можно переводить в “approved target architecture”.[^8][^3]

***

## 2. External Governance Findings

### Target vs candidate architecture

- **Evidence / source.**
    - Martin Fowler: ADR — короткий документ, фиксирующий одну архитектурную решение; решения не переписываются, а завершаются и при изменении supersede’ятся.[^4][^8]
    - Внутренний файл `01-v6-clean-lite-evidence-status.md` прямо отмечает, что **нет committed ADR для V6-clean-lite**, и рекомендует формулировку “candidate target architecture subject to ADR approval”.[^3]
- **Implication for this plan.** Нельзя называть V6-clean-lite “target architecture”, пока нет принятого ADR и результатов Phase 0–3. Это сейчас архитектурная гипотеза, даже если decomposition rationale сильный.[^2]
- **Recommended wording.**
    - В Executive Summary плана:
        - заменить “Целевая архитектура: V6-clean-lite… Это target architecture, а не conditional future option” на
        - “V6-clean-lite — **candidate target architecture** для `PlatformDropOldestExportSpanProcessor`, подлежащая подтверждению через PR‑0–PR‑3 и отдельный ADR.”


### ADR requirement for semantic changes

- **Evidence / source.**
    - Текущая реализация `shutdown()` не накладывает таймаут на `exporter.shutdown()`; `shutdownResult` может остаться навсегда незавершённым.[^7][^6]
    - Текущая реализация `forceFlush()` допускает гонку, при которой promise остаётся incomplete (R3).[^9][^6]
    - В `05-lifecycle-semantics-adr-evidence.md` прямо сказано: изменение semantics `shutdown()` / `forceFlush()` (bounded shutdown, deterministic forceFlush) — **ADR‑уровневое решение, а не “refactoring language choice”**.[^6]
    - OpenTelemetry Java SpanProcessor: `shutdown()` должен обработать все события до возврата; `forceFlush()` выполняется синхронно и не должен бросать исключений.[^10]
    - SpanExporter: `shutdown()` возвращает `CompletableResultCode`, который должен быть завершён при завершении операции.[^11]
- **Implication for this plan.** Любое введение bounded таймаута в `exporter.shutdown()` и устранение гонки `forceFlush + shutdown` изменяют наблюдаемое поведение (клиенты, делающие `shutdown().join()` или `forceFlush().join()`, увидят новые failure‑моды и таймауты) и поэтому требуют отдельного ADR.[^12][^6]
- **Recommended wording.**
    - В описании PR‑3 вместо “clean shutdown/forceFlush semantics” —
        - “PR‑3 реализует **ADR‑одобренные** изменения semantics shutdown/forceFlush (bounded shutdown, deterministic forceFlush), с отдельным decision record и тест‑гейтами.”


### JMX schema migration

- **Evidence / source.**
    - Текущий JMX путь: `PlatformExportControl` держит `Supplier<PlatformDropOldestExportSpanProcessor>` и трансформирует процессорные геттеры в MBean‑атрибуты (`getExportDroppedOverflowTotal()` и пр.).[^5]
    - Важно: имена Java‑геттеров процессора **отличаются** от имён MBean‑атрибутов; это два разных контракта.[^1][^5]
    - Нет ADR, фиксирующего wire‑contract для этих шести атрибутов; ссылка на `ADR-jmx-wire-map-contract.md` из constraints касается другого Map‑based протокола.[^1][^5]
    - JMX `MBeanServerConnection` при запросе несуществующего атрибута возвращает `AttributeNotFoundException`, что для внешних клиентов выглядит как явный breaking change.[^13]
- **Implication for this plan.**
    - Ломать Java‑геттеры процессора (перевод их на `MetricsSnapshot`) можно в pre‑prod, но изменение **имён MBean‑атрибутов** — это wire‑контракт и требует:
        - либо сохранения старых имен (Option A),
        - либо dual‑schema bridge,
        - либо формального ADR с описанием новой schema и миграции операторов.[^5]
- **Recommended wording.**
    - Вместо “JMX schema может быть переработана; backward compatibility не требуется” —
        - “JMX schema **может быть изменена**, но это **оператор‑фейсинг контракт**; любые изменения имён MBean‑атрибутов требуют отдельного ADR, инвентаризации JMX‑клиентов и миграционной документации. Java‑геттеры процессора могут быть заменены на `MetricsSnapshot`, сохранив существующие имена MBean‑атрибутов.”


### Pre-production breaking changes

- **Evidence / source.**
    - Таблица contracts в `02-contracts-and-migration-boundaries.md` выделяет: Builder/processor геттеры — internal Java API, JMX MBean атрибуты и config keys — runtime contract.[^1]
    - В `06-refactoring-constraints.md` геттеры процессора и Builder перечислены как hard constraints, но позже это частично переопределяется в evidence refresh.[^14][^2]
- **Implication for this plan.** Pre‑production режим даёт больше свободы для **внутренних** API (Builder, внутренний facade), но не отменяет ответственности за operator‑-facing контракты (JMX, rollback, opt‑in semantics). Их ломка требует явного ADR и operator‑миграции.[^2][^1]
- **Recommended wording.**
    - “Backward compatibility для Builder/JMX не является hard constraint” →
        - “Backward compatibility для **Builder/processor Java API** не является hard constraint в pre‑prod; **для JMX и opt‑in/rollback политики это остаётся governance‑решением**, оформляемым через ADR и операторскую миграцию.”


### OpenTelemetry shutdown / forceFlush behavior

- **Evidence / source.**
    - SpanProcessor (старый Java SDK): `shutdown()` должен завершить обработку всех span‑событий; `forceFlush()` выполняется синхронно и не должен бросать исключения.[^10]
    - Ruby/C++ SpanProcessor: `forceFlush(timeout)` и `shutdown(timeout)` принимают таймаут, но часто по умолчанию возвращают успех (best‑effort), что согласуется с текущей политикой `forceFlush after shutdown → ofSuccess`.[^15][^16]
    - `CompletableResultCode.join(timeout)` в Java SDK по issue \#2942 завершает результат как failure при таймауте; это обосновывает invariant `exportTimeouts ⊆ exportFailures`.[^17][^12]
- **Implication for this plan.**
    - Введение bounded exporter.shutdown() таймаута и детерминированного завершения forceFlush в PR‑3 делает поведение более предсказуемым и лучше согласованным с практикой bounded shutdown/forceFlush, но остаётся **семантической правкой**, а не чистым refactoring.[^15][^6]
- **Recommended wording.**
    - “PR‑3: clean shutdown/forceFlush semantics — bounded shutdown, deterministic forceFlush” →
        - “PR‑3 реализует **ADR‑одобренный bounded shutdown и deterministic forceFlush в соответствии с OpenTelemetry best practices**; изменения являются observable и сопровождаются отдельным ADR и тест‑гейтами.”


### Release gates for factory rollback

- **Evidence / source.**
    - Текущая default‑политика overflow: `DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST"`; rollback сейчас — **явное** `UPSTREAM`, а не “unset env”.[^18][^2][^1]
    - Factory wiring `PlatformExportProcessorFactory`: opt‑in, multi‑exporter fallback, no‑double‑export, shutdown stock BSP.[^18]
    - Test gates для PR‑4: explicit tests на UPSTREAM passthrough, multi‑exporter fallback, no‑double‑export, BSP key alignment.[^19][^18]
- **Implication for this plan.** Любые изменения factory wiring (PR‑4) должны иметь release‑гейты, подтверждающие: explicit UPSTREAM → stock BSP, multi‑exporter → stock BSP, отсутствие лишних worker threads и JMX‑регистраций в UPSTREAM‑путь.[^18]
- **Recommended wording.**
    - В разделе PR‑4: добавить явный “Rollback gates” блок с формулировкой “rollback = `overflow-policy=UPSTREAM`” и перечнем тестов, которые обязаны быть зелёными до merge.

***

## 3. Redline Recommendations

| Current plan language | Governance problem | Replacement wording | Source/evidence | Severity |
| :-- | :-- | :-- | :-- | :-- |
| “Целевая архитектура: V6-clean-lite … Это target architecture, а не conditional future option.”[^20] | Завышение уверенности: нет принятого ADR, нет PR‑0 результатов; репозиторий маркирует V6-clean-lite как гипотезу.[^3] | “V6-clean-lite — **candidate target architecture** для `PlatformDropOldestExportSpanProcessor`, подлежащая подтверждению через PR‑0–PR‑3 и отдельный ADR.” | [^3][^4] | High |
| “Backward compatibility для Builder/JMX не является hard constraint (pre-production).”[^20] | Смешивает два разных контракта: Builder — internal API; JMX — operator‑фейсинг wire‑контракт без ADR‑авторизации на разрыв.[^1][^2] | “Backward compatibility для **Builder/processor Java API** не является hard constraint в pre‑prod; изменения JMX MBean‑schema и rollback‑поведения рассматриваются как **семантические решения** и требуют ADR и миграции операторов.” | [^1][^2] | High |
| “JMX schema может быть переработана… старые attribute names не являются constraint (новая schema документируется как pre-production operator contract).”[^20] | Нет ADR для текущих MBean‑атрибутов; нет инвентаризации JMX‑клиентов; любое изменение имён атрибутов ломает клиентов (AttributeNotFoundException).[^5][^13] | “JMX MBean‑attribute schema **может быть изменена**, но это operator‑фейсинг контракт. Любое изменение имён атрибутов требует: (1) отдельного ADR, (2) инвентаризации JMX‑клиентов, (3) миграционной документации. Java‑геттеры процессора могут быть заменены на `MetricsSnapshot`, сохранив текущие MBean‑имена (Option A), либо через временный dual‑schema bridge (Option C).” | [^5][^1] | High |
| “PR-3: clean shutdown/forceFlush semantics — bounded shutdown, deterministic forceFlush, никогда не оставлять CompletableResultCode незавершённым.”[^20] | Формулирует явные semantic changes с bounded timeout и новой failure‑моделью как “clean semantics”, без явного ADR.[^6] | “PR‑3: реализовать **ADR‑одобренные изменения semantics shutdown/forceFlush**: bounded shutdown (таймаут и `shutdownResult.fail()` при истечении), deterministic forceFlush (все promises завершаются success/fail). Эти изменения документируются в отдельном ADR и проверяются тестами M1/M2/M3/M6.” | [^6][^10] | High |
| “Rollback by unsetting policy” (и эквивалентное в ADR‑v1).[^20][^2] | Фактическая ошибка: текущий default overflow‑policy = DROP_OLDEST, unset не возвращает UPSTREAM.[^1][^18] | “Rollback: **явно** выставить `platform.tracing.queue.overflow-policy=UPSTREAM` (или env `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`). Unset сейчас активирует DROP_OLDEST и не является rollback‑путём.” | [^1][^2] | Critical |
| “PR-3 deterministic forceFlush” (без уточнённого протокола).[^20] | Нет спецификации interlock‑протокола между ExportBuffer и ProcessorLifecycle; непонятно, как именно устраняется гонка R3.[^21][^6] | “PR‑3 должен реализовать deterministic forceFlush так, чтобы: (а) `forceFlush()` либо держит lock от проверки `shutdownRequested` до добавления в `pendingFlushes`, либо (б) `shutdownResult` закрывает все outstanding `pendingFlushes`; конкретный протокол фиксируется в ADR и в Component responsibilities (§7).” | [^21][^6] | High |
| “PR-4: заменить Builder … JMX сохранить как adapter; обновить factory wiring в том же PR” (без release‑гейтов).[^20] | Недостаточно явных gate’ов: opt‑in, multi‑exporter fallback, rollback проверяются косвенно; есть слабые тесты.[^18][^19] | “PR‑4: заменить Builder и переписать factory wiring **при наличии следующих gate’ов**: (1) explicit DROP_OLDEST path активирует новый Processor, (2) explicit UPSTREAM → stock `BatchSpanProcessor` (проверка типа), (3) multi‑exporter → stock BSP, (4) no double‑export, (5) rollback by UPSTREAM задокументирован и покрыт тестами.” | [^18][^1][^19] | High |


***

## 4. Required ADR Sections

### ADR: V6-clean-lite candidate architecture decision

- **Context.**
    - Монолит `PlatformDropOldestExportSpanProcessor` с девятью обязанностями и подтверждёнными рисками R1–R4.[^22][^3]
    - Пре‑production стадия, наличие сильного decomposition rationale, но отсутствие данных перф/стресс‑валидации и PR‑0 результатов.[^19][^2]
- **Decision.**
    - V6-clean-lite принимается как **candidate target architecture**: facade, ExportBuffer, ExportWorker, ProcessorLifecycle, TimedSpanExporter, DropOldestProcessorMetrics(+Snapshot), DropOldestExportProcessorConfig.[^21][^20]
- **Status.** `Proposed` до PR‑0–PR‑3 и последующего пересмотра.
- **Constraints.**
    - Lock ownership только в ExportBuffer; lifecycle state только в ProcessorLifecycle; queue+pendingFlushes под одним lock; no duplicate state machines.[^21]
- **Consequences.**
    - Упрощение сопровождения; необходимость новых unit/integration tests; зависимость PR‑2/PR‑3 от корректной реализации buffer/lifecycle интерлока.[^3][^19]
- **Validation.**
    - PR‑0 результаты (M1–M11), зелёные фундаментальные инварианты; отсутствие материальной перф‑регрессии после PR‑2/PR‑6.[^19]


### ADR: bounded shutdown

- **Context.**
    - Текущий `shutdown()` вызывает `exporter.shutdown()` без таймаута; `shutdownResult` может остаться incomplete навсегда.[^7][^6]
- **Decision.**
    - Добавить bounded timeout вокруг `exporter.shutdown()`; по истечении таймаута `shutdownResult.fail()`, логировать WARN; terminator поток завершается.[^6]
- **Status.** `Proposed`.
- **Behavior change.**
    - Новая failure‑модель для вызовов `shutdown().join(timeout)`: вместо вечного ожидания — предсказуемый FAIL при истечении времени.[^6]
- **Rationale.**
    - Соответствие bounded shutdown best practices; устранение R1‑hang; согласование с OTel практиками shutdown‑таймаута для экспортеров.[^11][^15]
- **Test gates.**
    - M3, M6 зелёные; `shutdownResult.isDone()` всегда true после bounded времени; ни один shutdown не остаётся навсегда незавершённым.[^19][^6]


### ADR: deterministic forceFlush

- **Context.**
    - Текущий `forceFlush()` может оставить promise навсегда incomplete при гонке с shutdown.[^9][^6]
- **Decision.**
    - Гарантировать, что всякий `forceFlush()` завершает возвращаемый `CompletableResultCode` (success/fail) во всех состояниях RUNNING/SHUTTING_DOWN/TERMINATED; устранить R3 гонку.[^6]
- **Status.** `Proposed`.
- **Behavior change.**
    - Клиенты, делающие `forceFlush().join(timeout)`, больше не видят “вечного ожидания”; вместо этого получают либо успех, либо явный FAIL.[^6]
- **Interlock protocol.**
    - Описать точный протокол взаимодействия ProcessorLifecycle ↔ ExportBuffer: либо удержание lock в forceFlush до добавления в pendingFlushes, либо завершение всех outstanding pendingFlushes при переходе в TERMINATED.[^21]
- **Test gates.**
    - M1/M2 зелёные; ни один promise не остаётся incomplete; forceFlush‑после‑shutdown (M7) → ofSuccess().[^19][^6]


### ADR: JMX schema migration

- **Context.**
    - Текущее JMX API через `PlatformExportControlMBean` с 6 атрибутами; нет ADR для этих атрибутов; план предлагает snapshot‑центричную схему.[^5][^1]
- **Decision.**
    - Выбор Option A/B/C: либо сохранить имена MBean‑атрибутов и менять только источник (snapshot), либо ввести новую schema с миграционным окном, либо dual‑schema на период.[^5]
- **Status.** `Proposed`.
- **Operator impact.**
    - Описать, какие JMX‑клиенты затрагиваются, и как они будут мигрировать на новую schema.[^5]
- **Test gates.**
    - JMX attribute round‑trip tests; DomainMBeanJmxCompliance; при Option A — backward‑compat; при Option B/C — явная проверка новых атрибутов.[^19][^5]


### ADR: rollback behavior correction

- **Context.**
    - Текущая документация утверждает “rollback by unsetting env”; фактически default = DROP_OLDEST; rollback = UPSTREAM.[^2][^1]
- **Decision.**
    - Исправить rollback‑контракт: “Rollback: явно задать `overflow-policy=UPSTREAM`; unset активирует DROP_OLDEST и не является rollback”.[^1]
- **Status.** `Accepted` после обновления ADR‑v1 и всех релевантных docs.
- **Test gates.**
    - Explicit UPSTREAM → stock BSP, no replacement, no JMX‑регистрация, нет лишних worker threads.[^18][^19]

***

## 5. Minimum Release Gates

### PR‑0 (характеризационные тесты + baseline)

- **Гейты (по `08-test-and-release-gates-refresh.md`).**[^19]
    - M1, M2, M4a, M5, M7, M8, M9, M10 активные (зелёные).
    - M3, M6 могут быть `@Disabled` **только** с ссылкой на issue/ADR; нельзя ослаблять assert.
    - M11: JMH baseline сохранён как артефакт.
- **Никакие must‑gates нельзя отключать:** M4a, M7, M10, M9.


### PR‑1 (config/exporter/metrics)

- **Гейты.**[^19]
    - Все активные PR‑0 тесты остаются зелёными.
    - Unit‑тесты для `DropOldestExportProcessorConfig`, `TimedSpanExporter`, `DropOldestProcessorMetrics(+Snapshot)` покрывают: парсинг `otel.bsp.*`, WARN+fallback, invariant `exportTimeouts ⊆ exportFailures`.
    - Factory wiring не трогается; никаких partial wiring новых компонентов.
- **Must‑pass tests:** все PR‑0 active; новые unit‑тесты для config/exporter/metrics.


### PR‑2 (buffer/worker/lifecycle + facade)

- **Гейты.**[^21][^19]
    - M10 (drop‑oldest, non‑blocking onEnd, exporter вне lock) обязательно зелёный.
    - Все existing tests зелёные; никаких новых disabled.
    - Старые геттеры facade могут временно делегировать в metrics.snapshot, если это миграционный мост, но их удаление — только PR‑5.
- **Нельзя отключать:** M10, M7, M4a, все overflow/queue characterization tests.


### PR‑3 (bounded shutdown + deterministic forceFlush)

- **Гейты (по lifecycle ADR evidence).**[^6][^19]
    - M1 (concurrent forceFlush+shutdown) **обязателен зелёный** — ни один promise не остаётся incomplete.
    - M2 (multiple concurrent forceFlush) зелёный.
    - M3 (shutdown hang) после введения bounded shutdown **должен стать активным и зелёным**: `shutdownResult` всегда завершается (success/fail).
    - M6 (shutdown‑timeout interrupt path) зелёный — нет зависания; worker корректно прерывается и завершает shutdown.
    - M7 (forceFlush after shutdown) продолжает возвращать `ofSuccess()`.
    - Ни один `CompletableResultCode` (shutdown/forceFlush) не остаётся permanent incomplete.
- **Нельзя отключать:** M1, M2, M3, M6, M7. Любой `@Disabled` в этих тестах недопустим на merge PR‑3.


### PR‑4 (Builder/API/JMX/factory wiring)

- **Гейты (по builder/factory evidence и contracts).**[^18][^1][^5][^19]
    - All PR‑0, PR‑2, PR‑3 tests зелёные.
    - Opt‑in activation: explicit DROP_OLDEST → новый processor; explicit UPSTREAM → stock `BatchSpanProcessor` (type assertion).
    - Multi‑exporter fallback: при двух exporters до customizer → stock BSP (type assertion, не просто not‑null).[^1][^18]
    - No‑double‑export: `BspDropOldestNoDoubleExportTest` зелёный.
    - SharedDefaultsAlignment / BuilderValidation / Defaults tests зелёные.
    - JMX tests: DomainMBeanJmxCompliance, round‑trip значений counters, queue size/capacity; при Option A — backward compat имён, при Option B/C — явный тест новых атрибутов.[^5]
    - Rollback gate: explicit UPSTREAM не создаёт новый processor, не регистрирует JMX, не запускает worker‑thread.[^18]
- **Нельзя отключать:** все factory‑интеграционные тесты, особенно UPSTREAM/multi‑exporter/rollback/no‑double‑export; JMX compliance tests.


### PR‑5 (remove legacy)

- **Гейты.**[^19]
    - Нет deprecated/bridge методов; весь transitional код, введённый в PR‑2/PR‑4, удалён.
    - Все тесты (включая M1–M11, JMX, factory, Builder) зелёные.
- **Нельзя отключать:** любые тесты, касающиеся legacy‑поведения (иначе можно спрятать миграционные дефекты).


### PR‑6 (JMH+stress)

- **Гейты.**[^19]
    - No material regression vs PR‑0 baseline (одинаковая среда/JDK/JVM/JMH‑параметры).
    - Stress: нет deadlock, нет lock‑leak, нет permanent incomplete `CompletableResultCode`.
- **Нельзя отключать:** stress‑тесты concurrency; отклонение JMH‑результатов без явного пересмотра перф‑требований.

***

## 6. Final Correction Checklist

1. Изменить формулировку архитектурной определённости: **“target architecture” → “candidate target architecture subject to ADR approval and Phase 0–3 validation”** во всех местах плана и связанных файлов.[^3]
2. Подготовить и закоммитить ADR для V6-clean-lite (`ADR-drop-oldest-export-processor-v6-clean-lite.md`) со статусом `Proposed`, включающий decomposition, state ownership, lock protocol и тест‑гейты.[^2][^21]
3. Подготовить отдельный ADR для bounded shutdown (`exporter.shutdown()` таймаут, `shutdownResult` fail on timeout) как явный semantic change; описать текущую и новую semantics с ссылками на исходник.[^6]
4. Подготовить ADR для deterministic forceFlush, явно описывающий протокол ProcessorLifecycle ↔ ExportBuffer и поведение in RUNNING/SHUTTING_DOWN/TERMINATED.[^21][^6]
5. Подготовить ADR для JMX schema migration: чётко разделить Java‑геттеры процессора и MBean‑атрибуты, выбрать миграционный вариант (A/B/C), описать операторский impact и тест‑гейты.[^1][^5]
6. Исправить rollback‑документацию: заменить “rollback by unsetting env‑var” на “rollback by explicit UPSTREAM” в плане и ADR‑v1; добавить rollback‑гейты для PR‑4.[^2][^18][^1]
7. Переписать формулировку “Backward compatibility для Builder/JMX не является hard constraint” в духе: Builder/API можно ломать в pre‑prod, JMX и rollback — нет without ADR.[^2][^1]
8. Уточнить, что изменения `logExportFailureOnce` (rate‑limit/structured logging) — observable change для SRE, требующий отдельного решения и координации, и не включать их в план как “можно сделать по пути” без ADR.[^17]
9. В разделе PR‑3 явно перечислить недопустимость `@Disabled` для M1/M2/M3/M6 и то, что цель этапа — устранить R1/R3, а не просто “улучшить lifecycle”.[^6][^19]
10. В разделе PR‑4 явно перечислить обязательные factory/JMX‑гейты: explicit DROP_OLDEST/UPSTREAM, multi‑exporter fallback → BSP, no‑double‑export, JMX compliance и rollback gates.[^18][^5][^19]
11. Внести в план явное требование: queue и pendingFlushes **никогда не разделяются по разным владельцам/lock’ам**; ExportBuffer остаётся единственным владельцем `queueLock` и `Condition`, ProcessorLifecycle и ExportWorker не имеют прямого доступа к ним.[^9][^21]
12. Обновить wording для JMX: вместо “schema may be redesigned, old names not a constraint” — описать три опции migration и зафиксировать, что изменение имён MBean‑атрибутов требует ADR и операторской миграции.[^13][^5]

Этот чек‑лист можно использовать как основу для redline‑правок плана и как вход в ваш новый ADR‑коммит.
<span style="display:none">[^23][^24][^25][^26][^27][^28][^29][^30][^31]</span>

<div align="center">⁂</div>

[^1]: 02-contracts-and-migration-boundaries.md

[^2]: 00-refresh-executive-summary.md

[^3]: 01-v6-clean-lite-evidence-status.md

[^4]: https://martinfowler.com/bliki/ArchitectureDecisionRecord.html

[^5]: 03-jmx-snapshot-migration-evidence.md

[^6]: 05-lifecycle-semantics-adr-evidence.md

[^7]: 01-current-behavior.md

[^8]: https://martinfowler.com/articles/scaling-architecture-conversationally.html

[^9]: 02-concurrency-model.md

[^10]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk/0.7.1/io/opentelemetry/sdk/trace/SpanProcessor.html

[^11]: https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/export/SpanExporter.html

[^12]: https://github.com/open-telemetry/opentelemetry-java/issues/2942

[^13]: https://docs.jboss.org/jbossas/javadoc/4.0.5/jmx/javax/management/MBeanServerConnection.html

[^14]: 06-refactoring-constraints.md

[^15]: https://oneuptime.com/blog/post/2026-02-06-otel-sdk-shutdown-java-spring-boot/view

[^16]: https://open-telemetry.github.io/opentelemetry-ruby/opentelemetry-sdk/v1.1.0/OpenTelemetry/SDK/Trace/SpanProcessor.html

[^17]: 07-logging-observability-policy.md

[^18]: 04-builder-config-factory-wiring-evidence.md

[^19]: 08-test-and-release-gates-refresh.md

[^20]: drop-oldest_refactoring_plan_262d2bc7.plan.md

[^21]: 06-buffer-lifecycle-state-ownership.md

[^22]: 00-executive-summary.md

[^23]: 10-perplexity-correction-input.md

[^24]: 09-plan-redline-input.md

[^25]: https://opentelemetry.io/docs/specs/otel/trace/sdk/

[^26]: https://opentelemetry.io/ko/docs/languages/java/sdk/index.md

[^27]: https://www.catio.tech/blog/architecture-decision-record

[^28]: https://github.com/open-telemetry/opentelemetry-java/blob/main/exporters/zipkin/src/main/java/io/opentelemetry/exporter/zipkin/ZipkinSpanExporter.java

[^29]: https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/797

[^30]: https://tessl.io/registry/tessl/npm-opentelemetry--sdk-trace-base/2.1.0/files/docs/span-processors.md

[^31]: https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java

