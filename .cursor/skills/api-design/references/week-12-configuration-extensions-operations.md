# Week 12 — Configuration properties и extension points

## Контекст

Configuration namespace, properties types, customizers, strategies — public API стартера наравне с Java signatures.

## Configuration properties как public API

### Namespace

**Must:** уникальный namespace. **Must-not:** занимать `spring`, `server`, `management`.

Корпоративная форма: `company.platform.<capability>.*`

**Must:** namespace устойчив к замене vendor. `company.platform.tracing.export.timeout` лучше, чем `company.platform.otel.batch-span-processor-timeout`, если vendor detail не намеренный API.

### Properties object — boundary DTO

**Must-not:** внедрять runtime beans в `@ConfigurationProperties`. Properties описывает Environment.

Разделение:

```text
raw operator input → bound properties → validated/normalized domain config → constructed runtime graph
```

## Проектирование properties types

### Typed values (preferred)

| Вместо | Использовать |
|---|---|
| числовой timeout | `Duration` |
| long bytes | `DataSize` |
| closed modes | `enum` |
| связные options | nested groups |
| domain ids | value objects/converters |
| immutable value | constructor binding |

Тип **must-not** заменять semantics: у `Duration` определить zero, negative, maximum, default unit.

### Records

Record — compact immutable config, constructor binding. Component list жёстко связан с canonical constructor и metadata.

**Must:** для большого часто эvolving namespace — class с nested groups иногда даёт управляемую source compatibility.

### Metadata

Configuration processor генерирует IDE metadata. Record components — `@param` в Javadoc.

**Must:** generated JSON как release artifact: key, type, description, default, deprecation, hints.

## Defaults, validation, normalization

Три стадии:

```text
external values → bound properties → validated/normalized domain config
```

- Binding — преобразование формы
- Bean Validation — локальные constraints
- Domain normalizer — cross-field invariants, effective values

Cross-field правило (например `requestTimeout >= connectTimeout`) → отдельный validator/factory с domain diagnostic.

### Один source of truth для default

**Must-not:** default одновременно в field initializer, `@DefaultValue`, factory, vendor SDK.

Properties metadata, documentation, runtime test **must** показывать один default.

### Fail fast

Невалидная статическая конфигурация → startup с actionable message.

**Must-not:** validation открывает network connection для remote availability check. External availability — readiness concern, если contract не требует startup handshake.

## Programmatic extension points

Properties недостаточны для: credentials provider, serializer, classifier, naming strategy, retry policy, client customizer, callback observer.

### Выбор формы

| Форма | Подходит для | Архитектурная обязанность |
|---|---|---|
| Replacement bean | полная замена component | backoff, dependent graph |
| Customizer | настройка builder/object | ordering, idempotency, mutation scope |
| Strategy | алгоритмическая policy | input/output/failure contract |
| Provider | resource/value во времени | lifecycle, caching, failures |
| Observer | events | thread, ordering, exception isolation |
| Registry/contributor | множество расширений | identity, conflicts, merge |

**Must:** выбирать по семантике, не по удобству `List<Bean>` injection.

## Customizers

**Must-not:** customizer владеет lifecycle, запускает background threads, сохраняет ссылку на partially built object (если не заявлено).

Контракт **must** определить:

- сколько раз вызывается
- порядок
- можно ли менять одни fields повторно
- кто выигрывает при конфликте с properties
- разрешён ли exception
- до или после platform hardening

## Strategy vs boolean forest

Behavior с самостоятельной semantics → отдельная strategy. Properties выбирают built-in; bean seam — custom implementation.

## Provider

Provider ≠ value: результат меняется, вычисляется лениво. **Must:** cache, refresh, thread safety, stale behavior, failure propagation.

**Must-not:** `Supplier<T>` без определённого протокола.

## Multiplicity, ordering, conflicts

`List<Customizer>` injection → merge protocol **must** определить:

- сортировка (`Ordered`/`@Order`/priority)
- direction: меньшее раньше или позже
- tie-break для равного order
- stable identity contributors
- конфликт полей
- дедупликация
- exception policy

**Must-not:** порядок от classpath enumeration, component scan, registration accident.

### Reserved order bands (пример)

```text
-1000..-501  mandatory platform pre-processing
-500..499    application customizers
500..999     platform finalization/validation
```

Reserved bands — public contract, документированы. **Must-not:** скрытый обход user overrides.

## Coexistence и backoff graph

Default client связан с serializer, credentials, health, lifecycle coordinator.

User заменяет client → **must** решить для dependent defaults:

- остаются применимыми
- back off вместе
- требуют adapter
- становятся недопустимыми

Backoff проектируют как **graph**, не annotation на одном factory method.

### Partial override (опасный сценарий)

```text
user provides Client
starter backs off Client bean
starter still creates DefaultClientHealthIndicator expecting DefaultClient internals
```

**Must:** перечислить supported override combinations; запретить остальные с понятной диагностикой.

## User configuration precedence

Auto-configuration дополняет application, отступает перед user configuration.

**Must-not:** «user wins» + backoff на слишком общий `Object`, `Executor`, vendor interface — случайный bean отключает feature.

### Properties vs programmatic

Precedence **must** быть явной:

```text
hard platform invariants → properties → ordered customizers → validation
```

или

```text
properties → application customizers → platform safety finalizer
```

Вторая модель — только если finalizer ограничен неотменяемыми safety requirements.

**Must-not:** starter обещает customization, но молча перезаписывает через finalizer.

## Failure architecture

| Категория | Timing |
|---|---|
| unsupported dependency version | startup |
| missing mandatory property | startup |
| invalid cross-field config | startup |
| ambiguous providers | startup |
| incomplete replacement graph | startup |
| unavailable optional integration | startup или degraded |
| external system unavailable | readiness, не restart loop |
| resource exhaustion | runtime |

### Actionable startup failure

`FailureAnalyzer` → Description + Action. **Must-not:** скрывать cause.

```text
Description: Two PolicyProvider beans have the same priority 100.
Action: Assign distinct orders or configure company.platform.policy.primary-provider.
```

**Must-not:** catch bootstrap exception, warning, partial graph — расширяет production states.

Fail-open — только явная domain policy с operational signal.

## Operational architecture

**Must** определить:

- metrics: always vs conditional, bounded cardinality labels
- health component names, status mapping
- readiness/liveness semantics
- stable diagnostic codes
- startup summary без secrets
- runtime controls, security boundary
- shutdown behavior

Actuator integration — **optional slice**, если base feature не требует Actuator.

Condition report — development. Production — stable state summary: active provider, mode, disabled reason. **Must-not:** credentials или full Environment.

## Ключевые тезисы

1. Configuration namespace, defaults, precedence — public API.
2. Properties object описывает Environment; factory строит domain config.
3. Customizer, strategy, provider, observer — разные протоколы.
4. Collection injection требует deterministic ordering.
5. Backoff — graph, не один bean.
6. Structural misconfiguration и external outage — разный failure timing.
7. Operational surface входит в архитектуру starter.
