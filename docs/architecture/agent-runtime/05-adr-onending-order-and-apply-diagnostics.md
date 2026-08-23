# ADR: порядок onEnding и диагностика apply

Статус: ACCEPTED
Дата: 2026-08-23

Существующий ADR, который владел бы pre-enrichment producer-валидацией
`platform.trace.result` или диагностическим снапшотом control-plane apply,
в репозитории не найден. Этот документ фиксирует оба решения.

## Контекст

Qualification `jmx-direct` зафиксировал два дефекта одной сборки:

- **C40.** При `validationEnabled=true` маркер `platform.validation.missing`
  не появлялся: `EnrichingSpanProcessor` нормализовал `platform.trace.result`
  раньше, чем `ValidatingSpanProcessor` успевал увидеть отсутствие
  producer-объявления.
- **B31.** Конкурентный apply после успешного commit политики возвращал
  HTTP 500: `LastSettingsUpdateSnapshot.Timestamps.advance` отвергал
  `observedAt`, штампованный при decode раньше записи диагностики.

Оба дефекта — следствие неверной модели владения, а не недостаточной
защиты в месте падения.

## Решение 1. Producer владеет `platform.trace.result`

Владелец `platform.trace.result` — producer или instrumentation, а не
ending-фаза enrichment. Production-писатели: `TracedAspect`,
`ExceptionRecorder`, `OwningSpanScope`, публичный enrichment API,
Observation-конвенции servlet/WebFlux, `SpanWatchdogProcessor` (значение
`timeout` при force-end). `EnrichingSpanProcessor.applyDefaultResult`
подставляет значение только если ключ ещё отсутствует.

Следствие для `onEnding`: factory регистрирует делегатов в порядке

```text
validation → enrichment → classification / watchdog / metrics → scrubbing
```

`PlatformSpanProcessorFactory` владеет этим порядком.
`PlatformCompositeSpanProcessor` и `DelegateWiring` только сохраняют
порядок регистрации. Отдельный pipeline-stage enum, registry или SPI
не вводятся.

Точная граница validation — состояние span после start-фазы enrichment
и до её ending-фазы. Формулировка «до enrichment» неточна: start-фаза
уже выставила `platform.trace.type`. Поэтому при включённом enrichment
проверка `platform.trace.type` почти всегда тривиальна; живой она
остаётся при отключённом enrichment. Набор `REQUIRED_ATTRIBUTES` не
сокращается.

Маркер `platform.validation.missing` фиксирует пробел producer-контракта
конкретного span'а, а не дефект итогового экспортируемого span'а.
Маркер пишется до scrubbing и подлежит sanitization.
`validationViolationsTotal` считает span'ы без producer-объявленного
`platform.trace.result` (и без `platform.trace.type`, если enrichment
выключен). Per-span снимок producer-состояния в `onStart` не вводится.

Дефолт `platform.tracing.validation.enabled` этим ADR не меняется.
Если почти универсальный маркер в agent-only деплоях неприемлем,
рычаг — этот дефолт, а не порядок процессоров.

## Решение 2. Диагностика apply — recording-order merge

`LastSettingsUpdateSnapshot` хранит точные `outcomeCounts` и когерентный
кортеж `latestAttempt`, `latestOutcome`, `latestMessageCode`.
Вложенный record `Timestamps` и поля `lastAttemptAt`,
`lastSuccessfulEvaluationAt`, `lastAcceptedChangeAt`,
`lastUnsuccessfulAttemptAt` удалены: у них не было читателей вне самого
перехода. Merge несуществующих меток не описывается.

Переход — чистая функция. «Последнее» событие — то, чей вызов
`AtomicReference.updateAndGet` линеаризован последним. Wall-clock order
не является частью state-модели. Любой порядок завершения потоков даёт
согласованный снапшот без исключений.

Запись без canonical attempt (`nextWithoutAttempt`) обнуляет
`latestAttempt` и обновляет `latestOutcome` / `latestMessageCode` тем же
событием. Смешанный кортеж запрещён. Pre-apply ветка
`forPreApplyDiagnostics` не меняется: отклонённый apply с валидным UUID
по-прежнему echo'ит `LastAttemptId`.

`diagnostics.record` остаётся вне `try/catch` вокруг `evaluate`.
Успешный `APPLIED` не маскируется в `FAILED` и не глотается.
Публикация остаётся lock-free на одном `AtomicReference`.

## Последствия

- Agent-only приложения с включённой validation получают маркер на
  каждом span без producer-объявленного result. Это правдивая
  диагностика, а не шум factory.
- Spring-стартеры закрывают типичный server/client путь конвенциями,
  `@Traced`, `ExceptionRecorder` и `OwningSpanScope`; маркер там
  исключителен.
- JMX отдаёт `LastApplyOutcome`, `LastAttemptId`, `LastFailureCode` и
  счётчики. Наблюдаемого last-attempt timestamp у apply-диагностики нет.
- `SettingsUpdateAttempt.observedAt` остаётся полем попытки: его читают
  через `latestAttempt()`, а не через удалённый `Timestamps`.

## Отклонённые альтернативы

### Validation после enrichment

Проверяла бы итоговый span. Enrichment — fallback, поэтому маркер
перестал бы когда-либо появляться. C40 остался бы зелёным только ценой
потери смысла каталога.

### Два процессора raw/final или снимок в `onStart`

Состояние на каждый span ради диагностического маркера. Владение ключом
уже достаточно, чтобы один `onEnding`-проход видел producer-контракт.

### Монотонный merge четырёх timestamps

Поля write-only. Шесть кейсов на null-safe max и тай-брейк описывали бы
данные без потребителей и оставляли бы B31 возможным при любой ошибке
сравнения.

### `try/catch` вокруг `diagnostics.record`

После успешного commit превращал бы наблюдаемый apply в `FAILED` либо
скрывал бы рассинхрон диагностики. Нарушает атомарность apply.

## Совместимость

Решение pre-production. Алиасы, `@Deprecated` и dual-path не вводятся.
Порядок `onEnding` и форма снапшота — текущий контракт, а не миграция
со старого.

## Верификация

- `factory_preserves_required_on_ending_order` — `validation < enrichment
  < scrubbing`, scrubbing последний.
- Factory-backed behavioral тест: span без producer-result получает
  маркер, итоговый result `success`, delta
  `validationViolationsTotal == 1`; span с producer-result — маркер
  отсутствует, delta `0`.
- `PipelinePolicyCharacterizationTest` собирает тот же порядок
  компонентно и проверяет выключенную validation.
- `LastSettingsUpdateSnapshotTest` — recording-order задаёт `latest*`,
  запись без attempt обнуляет `latestAttempt`.
- `LastSettingsUpdateConcurrencyTest` — 32 потока, non-baseline
  candidate, ровно один `APPLIED`, когерентный latest-кортеж, нет
  `FAILED` / `INTERNAL_FAILURE`.

## Открытые решения

Дефолт `platform.tracing.validation.enabled` для agent-only деплоев.
Смена затрагивает baseline A-сценариев и решается отдельно.

## Остаточный риск

Attestation и factory-гейт не доказывают, что каждый production
instrumentation-путь объявляет result. В agent-only контуре маркер
останется частым, пока producer-контракт не появится у соответствующей
инструментации.
