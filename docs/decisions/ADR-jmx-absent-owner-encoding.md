# ADR: JMX absent-owner encoding (presence discriminator)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Связанные | [ADR-jmx-observation-wire-contract](./ADR-jmx-observation-wire-contract.md), [ADR-jmx-source-identity-model](./ADR-jmx-source-identity-model.md) |

## Статус

Принято.

## Контекст

На этапе разработки, до первой публикации схемы, per-owner observation-члены (`Sampling`, `Scrubbing`, `Validation`) кодировали отсутствие owner sentinel-значениями: `ownerGeneration = -1L`, строковые поля `"unknown"`. Sentinel'ы неотличимы от потенциально валидных данных, требуют special-case в decode/export и нарушают принцип явного контракта. `Export` и `ProcessorMetrics` уже использовали `ownerPresent`/`ownerGeneration`. Sentinel-кодирование было отклонено до выхода решения в production — отдельного опубликованного релиза под ним не существовало, поэтому в нормативном контракте version 1 presence-дискриминатор применяется во всех per-owner членах с самого начала.

Micrometer gauge API не различает причину отсутствия числового значения; нужна явная модель read vs absent vs violation без ложной интерпретации wire item'ов.

## Решение

Sentinel-кодирование **заменено** парой presence-дискриминаторов в per-owner sub-composite:

| Item | OpenType | Правило |
|------|----------|---------|
| `ownerPresent` | BOOLEAN | `true` — owner зарегистрирован на момент capture |
| `ownerGeneration` | LONG | при `ownerPresent=true` — строго положительное; при `ownerPresent=false` — generation последнего известного owner (диагностика) |

**`ownerGeneration = 0` запрещено при `ownerPresent = true`.**

### Контракт при absent owner

Когда `ownerPresent = false`, owner-specific item values **не являются частью domain-контракта и MUST NOT интерпретироваться или экспортироваться** (нормативная формулировка wire-контракта). Декодер обязан игнорировать payload item'ы владельца; binders не создают meter tags из «мёртвых» значений.

### Доменная семантика read

Валидное observation с absent owner классифицируется как **`JmxRead.Present`**: `domain.readable = 1`, `owner.present = 0`. Это **не** contract violation.

| Surface | Поведение |
|---------|-----------|
| Point-in-time gauges owner | `NaN` |
| `MonotonicJmxCounterGroupBridge` | без изменений (monotonic state сохраняется) |
| Registration / readable gauges | отражают `owner.present = 0` |
| `jmxContractDiagnostics` | фиксирует absent owner явно |

### Micrometer compromise

Значение point-in-time gauge **не различает** `Absent`, `ContractViolation` и `ExecutionFailure` — во всех случаях **`NaN`**. Различие обеспечивается:

- registration- и readable-gauges (`owner.present`, `domain.readable`);
- `jmxContractDiagnostics`;
- counters violation/failure — когда owner присутствует и decode/execution завершился ошибкой контракта или runtime.

Это сознательный компромисс: Micrometer `Gauge` не поддерживает typed absence; дублирование sentinel'ов в metric value запрещено.

`SCHEMA_VERSION = 1` ([ADR-jmx-observation-wire-contract](./ADR-jmx-observation-wire-contract.md)) — presence-дискриминатор входит в нормативный контракт с первой публикации; sentinel decode path существовал только в рабочих ревизиях до выхода в production и в опубликованной схеме отсутствует, compatibility alias для него не создавался.

## Последствия

- Independent-verification тесты на agent и autoconfigure проверяют согласованность `ownerPresent`/`ownerGeneration` правил, а не tautological parity одной константы.
- Dashboards и alerts должны использовать `owner.present` и diagnostics gauges, а не интерпретировать `NaN` gauge как единственный сигнал.
- Новые per-owner observation-члены обязаны использовать presence discriminator; sentinel `-1L` / `"unknown"` запрещены и не существуют ни в одной опубликованной версии схемы.
- `Diagnostics` member не менялся: его источники — статические синглтоны без reset; `ownerPresent` для них константен.
