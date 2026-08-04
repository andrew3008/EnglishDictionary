# ADR: JMX source identity model (agentInstanceId и generation)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Связанные | [ADR-jmx-observation-wire-contract](./ADR-jmx-observation-wire-contract.md), [ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md), [ADR-autoconfigure-boundaries-and-jmx-parity](./ADR-autoconfigure-boundaries-and-jmx-parity.md) |

## Статус

Принято.

## Контекст

JMX observation и Micrometer counter bridge должны корректно обрабатывать смену владельца runtime-состояния (reset sampler, replace processor owner) без двойного учёта или «отката» счётчиков. Идентичность источника должна быть однозначной across agent capture, wire frame и application-side decode.

Ранее generation и process identity смешивались или публиковались в разных атомарных шагах, что допускало наблюдение нового owner до выделения generation (reverse publication).

## Решение

Принята модель **D**: двухуровневая идентичность источника.

### Process identity

`agentInstanceId` — UUID, идентифицирующий экземпляр agent-процесса. Передаётся в frame каждого observation-члена ([ADR-jmx-observation-wire-contract](./ADR-jmx-observation-wire-contract.md)). Смена process identity означает новый JVM/agent lifecycle.

### Per-owner generation

Каждый mutable owner (sampler, scrubber, processor registry slot и т.п.) регистрируется через `OwnerRegistrationSlot`. **Generation** выделяется **внутри того же** `atomic updateAndGet`, который публикует нового owner. Публикация owner без одновременного bump generation запрещена — это предотвращает reverse publication.

При `ownerPresent = false` generation сохраняется для диагностики последнего известного owner; см. [ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md).

### Micrometer MonotonicJmxCounterGroupBridge

Counter bridge ключует monotonic state парой **`(agentInstanceId, generation)`**. Смена generation при том же process identity сбрасывает bridge с причиной `OWNER_REPLACED`. Смена `agentInstanceId` — с причиной `AGENT_INSTANCE_CHANGED`.

`AGENT_INSTANCE_CHANGED` — **защитная** ветка: в нормальной in-process javaagent-топологии (один JVM, один agent extension) она недостижима. Поведение проверяется только unit-тестами с инъекцией supplier'а `agentInstanceId`, а не production E2E.

### Компромисс закрыт: bridge потребляет wire `agentInstanceId`

Decode path на стороне autoconfigure использует **generation из `JmxOwnerState`**, полученного из wire sub-composite. `agentInstanceId` **surfaced** во все шесть decode DTO (`*Observation`) и потребляется Micrometer bridge через supplier, читающий это поле — например,
`() -> client.sampling().observation().map(SamplingObservation::agentInstanceId).orElseGet(MonotonicJmxCounterGroupBridge.LOCAL_PROCESS_IDENTITY)`.
`RuntimeMXBean#getName()` (`MonotonicJmxCounterGroupBridge.LOCAL_PROCESS_IDENTITY`) остаётся **только fallback**: применяется исключительно когда observation недоступен (transport/registration failure), не как основной путь. Прежний Slice 0 компромисс (process-scoped identity по умолчанию) закрыт.

## Последствия

- Любая новая точка регистрации owner обязана использовать `OwnerRegistrationSlot` (или эквивалент с atomic co-publication), а не отдельный increment generation.
- Counter metrics остаются monotonic per `(agentInstanceId, generation)`; gauge snapshot при absent owner — NaN, bridge counters не сбрасываются ([ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md)).
- Все binders на базе `MonotonicJmxCounterGroupBridge` обязаны передавать wire-based supplier `agentInstanceId` (frame-поле соответствующего `*Observation`), а не `LOCAL_PROCESS_IDENTITY` напрямую — последний используется только как `orElseGet` fallback.
- Тесты bridge обязаны покрывать `OWNER_REPLACED`; `AGENT_INSTANCE_CHANGED` — только injected unit scenario.
