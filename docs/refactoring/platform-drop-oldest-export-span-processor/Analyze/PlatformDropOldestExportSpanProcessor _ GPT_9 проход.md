<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# Review the attached final arbitration produced by Claude Sonnet 4.6.

Act as a skeptical principal Java/OpenTelemetry architect.
Find unsupported assumptions, weak scoring decisions, risky recommendations, and missing production constraints.
Do not rewrite the full plan. Return only corrections and objections that should be applied before ADR approval.

Ниже — только те **исправления и возражения**, которые стоит внести до ADR‑аппрува.

## Критичные возражения

- **Не фиксируйте V6 как “рекомендуемую долгосрочную цель” в самом ADR.** Репозиторные материалы подтверждают, что C1/C2/C5 — это именно high-risk seams, а decomposition dossier прямо говорит “analysis only, not endorsed”; для C2 и C5 риск извлечения высокий из‑за общей lock/thread coupling.[^1][^2]
- **Не объявляйте V5 безусловно лучшим near-term path до Phase 0 результатов.** Из доказательств следует только то, что C3/C6/C7/C8 — low-risk extraction candidates; из этого не следует, что V5 безопаснее V1 в реальной системе до появления M1–M9 и benchmark baseline.[^3][^4][^2]
- **Если в арбитраже предлагается завершать `shutdownResult` успехом при timeout `exporter.shutdown()`, это нужно снять или отдельно эскалировать как semantic change.** Текущий код завершает `shutdownResult` по результату `exporter.shutdown().whenComplete(...)`; “timeout => succeed” меняет наблюдаемое поведение shutdown и может скрыть реальный exporter failure.[^5][^1]
- **Не добавляйте новый публичный/ JMX‑видимый счётчик без отдельного контракта.** Репозиторные ограничения фиксируют шесть JMX getter’ов и их wire contract; любые новые JMX‑атрибуты требуют осознанной схемной миграции, а не “по пути” в рамках hardening.[^4][^6]
- **Не переносите focus ADR с production safety на structural cleanup.** Основные подтверждённые риски сейчас — R1 exporter.shutdown hang, R2 non-standard unlock path, R3 forceFlush promise loss, R4 worker death; это reliability issues, а не maintainability-first issues.[^6][^1]


## Слабые или неподдержанные допущения

- **Численные scores и ranks нельзя подавать как evidence-based.** В репозиторных файлах есть variant definitions и risk framing, но нет эмпирической базы, которая обосновывает точные числа вроде 87/82/71; это decision aid, а не доказательство.[^7][^2]
- **Порог benchmark regression вида “baseline * 1.20” выглядит произвольным.** В доказательной базе есть требование “JMH baseline must not regress materially”, но нет конкретного допустимого процента, доверительного интервала или CI noise envelope.[^3][^4]
- **Извлечение `LifecycleState` как отдельного шага переоценено.** Репозиторий подтверждает, что `shutdownRequested` — hot-path fast read в `onEnd` и `forceFlush`; выгода от обёртки минимальна, а touching hot path ради тривиальной инкапсуляции — лишний churn без явной production value.[^2][^5][^4]
- **Извлечение `ObservabilitySnapshot` / observability holder не должно продаваться как значимый архитектурный выигрыш.** Текущая модель getter’ов намеренно не даёт консистентный snapshot across counters; это existing characteristic, и вынос в отдельный класс сам по себе не решает ни одного из high-risk defects.[^5][^2]
- **Формулировка про “worker crash counter” unsupported как обязательная часть плана.** В материалах подтверждены четыре существующих AtomicLong counters и шесть getter’ов; добавление нового internal counter возможно, но не обосновано как обязательная ADR decision.[^4][^5]


## Рискованные рекомендации

- **Не одобряйте extraction C1 без одновременного explicit design for the interrupt path.** Dossier прямо говорит, что extracting queue without co-design with worker merely moves the maintenance hazard across class boundaries.[^1][^2]
- **Не одобряйте extraction C2 как отдельный PR.** Риск worker extraction высокий именно потому, что early unlock path зависит от queue lock ownership; это не “обычный extract class”.[^2][^1]
- **Не ослабляйте постоянный one-shot throttle `logExportFailureOnce` без отдельного observability review.** Репозиторные ограничения прямо считают его observable behavior, на который SRE может полагаться.[^5][^4]
- **Не подменяйте forceFlush correctness “best effort” формулировками.** Текущий риск сформулирован жёстко: promise может остаться навсегда incomplete в shutdown race; ADR должен требовать completion guarantee (success or fail), а не просто “улучшить координацию”.[^3][^1]


## Недостающие production constraints

- **В merge gates нужно явно включить не только M1–M9, но и уже существующие integration guards.** Особенно важны `BspDropOldestNoDoubleExportTest` и autoconfigure tests на opt-in/fallback/multi-exporter behavior; это production wiring, а не второстепенные тесты.[^3]
- **В ADR нужно явно запретить изменение factory/wiring semantics.** Процессор создаётся через `PlatformExportProcessorFactory`, активируется только при explicit `DROP_OLDEST`, а multi-exporter path должен продолжать fallback на stock BSP.[^7][^6][^3]
- **Нужно явно сохранить constraint “implements SpanProcessor, not ExtendedSpanProcessor”.** Это не cosmetic detail: смена интерфейса меняет composite behavior в SDK pipeline.[^4]
- **Нужно явно сохранить constraint “Builder остаётся public static final inner class”.** Вынести validation/config можно, но наружный Builder API и его shape — frozen integration point с factory/tests.[^5][^4]
- **Нужно явно сохранить atomic coupling `pendingFlushes` + queue under the same `queueLock`.** Это не просто implementation detail; dossier прямо фиксирует общую lock‑visibility invariant, и любой ADR, допускающий независимый flush coordinator раньше queue redesign, недосказан.[^1][^2][^4]
- **Нужно явно зафиксировать, что `droppedSpansOverflow` инкрементируется атомарно с eviction под тем же lock.** Это один из ключевых поведенческих инвариантов, а не просто удобный счётчик.[^4][^5]


## Что поправить в решении по вариантам

- **V1 должен остаться полноценным contender, а не просто fallback.** С учётом production-critical статуса и того, что основные риски лежат в shutdown/flush/worker correctness, “status quo + targeted fixes” может быть самым defensible near-term outcome даже после Phase 0.[^6][^7]
- **V5 стоит оставить как “preferred first structural option after Phase 0”, но не как заранее утверждённую destination.** Это лучше согласуется с evidence level: low-risk candidate, но ещё не доказанный safest production choice.[^2][^3]
- **V6 нужно перевести из “recommended target” в “postpone / conditional future option”.** Иначе ADR обещает архитектуру, для которой в evidence base нет достаточного подтверждения deliverability и performance neutrality.[^7][^2][^4]


## Итоговое условие перед ADR approval

- **ADR можно двигать дальше только как conditional plan, а не как утверждённую дорожную карту до V6.** До approval нужно ослабить жёсткие claims про variant ranking, убрать/уточнить semantic changes around shutdown timeout handling, и явно добавить недостающие hard constraints по factory wiring, Builder shape, `SpanProcessor` contract, `pendingFlushes` lock coupling и integration test gates.[^6][^3][^4]

<div align="center">⁂</div>

[^1]: 02-concurrency-model.md

[^2]: 04-responsibility-decomposition.md

[^3]: 05-test-coverage-and-gaps.md

[^4]: 06-refactoring-constraints.md

[^5]: 01-current-behavior.md

[^6]: 00-executive-summary.md

[^7]: 07-llm-research-input.md

