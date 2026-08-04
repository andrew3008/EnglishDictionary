# ADR: JMX observation wire-контракт

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Связанные | [ADR-autoconfigure-boundaries-and-jmx-parity](./ADR-autoconfigure-boundaries-and-jmx-parity.md), [ADR-jmx-source-identity-model](./ADR-jmx-source-identity-model.md), [ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md) |

## Статус

Принято.

## Контекст

JMX observation-атрибуты передают диагностику runtime (Sampling, Scrubbing, Validation, Export, ProcessorMetrics, Diagnostics) из agent classloader в application plane через OpenMBean `CompositeData`. Общий Java-тип между `platform-tracing-otel-javaagent-extension` и `platform-tracing-spring-boot-autoconfigure` невозможен из-за изоляции classloader ([ADR-autoconfigure-boundaries-and-jmx-parity](./ADR-autoconfigure-boundaries-and-jmx-parity.md)).

Без единого нормативного wire-контракта имена item'ов, версия схемы и классификация ключей динамических карт расходятся между сторонами; parity-тест, сверяющий одну константу с самой собой, не даёт независимой гарантии.

## Решение

Шесть domain-level observation-членов (`Sampling`, `Scrubbing`, `Validation`, `Export`, `ProcessorMetrics`, `Diagnostics`) образуют **единственный** wire-контракт между agent и autoconfigure classloader'ами. Контракт фиксируется независимыми копиями `JmxObservationWireContract`:

- agent: `space.br1440.platform.tracing.otel.javaagent.jmx.observation`
- autoconfigure: `space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire`
  (пакет переименован из `observation/`, чтобы не конфликтовать с шестью `*Observation` DTO;
  `JmxOwnerState` живёт в том же `wire/` пакете как presence decode-модель)

Общий Java-тип или shared module для этих констант **запрещён**. Эквивалентность имён `CompositeType`, item'ов и `SCHEMA_VERSION` проверяется independent-verification тестами на каждой стороне.

Текущая версия схемы: **`SCHEMA_VERSION = 1`**. Решение не выходило в production ни в одной из промежуточных форм контракта, поэтому первая опубликованная схема зафиксирована как версия 1 — без отдельного «v1» релиза под sentinel-кодированием absent owner, которое существовало только в рабочих ревизиях до публикации (см. [ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md)). Любое breaking-изменение имён, типов OpenType, обязательности item'ов или семантики ключа после публикации повышает `schemaVersion`; потребители отклоняют неподдерживаемую версию.

Декодер каждого `*JmxView` дополнительно проверяет, что имя полученного `CompositeType` совпадает с нормативным (`*_COMPOSITE_TYPE_NAME`/`*_VALUES_TYPE_NAME`); несовпадение классифицируется как `COMPOSITE_TYPE_NAME_MISMATCH` и отклоняется тем же путём, что и `SCHEMA_VERSION_MISMATCH`, — раньше, чем декодер обратится к отдельным item'ам composite.

### Общий frame (все шесть членов)

| Item | OpenType | Семантика |
|------|----------|-----------|
| `schemaVersion` | INTEGER | версия wire-схемы observation |
| `captureSequence` | LONG | монотонный per-MBean счётчик захвата (diagnostic invariant) |
| `agentInstanceId` | STRING | UUID процесса агента в канонической строковой форме |
| `captureStartedAt` | LONG | epoch millis начала захвата |
| `captureCompletedAt` | LONG | epoch millis завершения, `>= captureStartedAt` |

Per-owner sub-composite и payload item'ы определяются контрактом каждого члена; кодирование absent owner — [ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md).

### Семантика доставки

Каждый `getAttribute` observation-члена возвращает **свежий** snapshot: agent не кэширует предыдущий `CompositeData` между вызовами. `captureSequence` монотонен per ObjectName и не участвует в ordering registry на стороне binders.

Класс `JmxObservationWireContract` содержит только нормативные имена и классификацию ключей; логика encode/decode принадлежит agent MBean и autoconfigure `*JmxView`.

## Последствия

- Новый observation-член или изменение frame требует синхронного обновления обеих копий контракта, independent-verification тестов и bump `schemaVersion`.
- Parity между classloader'ами достигается сравнением констант across modules, а не общим dependency.
- Binders и Micrometer bridge потребляют декодированное доменное представление; прямой доступ к wire item'ам вне adapter-слоя запрещён.
- v1 sentinel-кодирование (`-1L`, `"unknown"`) superseded; см. [ADR-jmx-absent-owner-encoding](./ADR-jmx-absent-owner-encoding.md).
