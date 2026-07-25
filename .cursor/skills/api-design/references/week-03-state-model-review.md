# Неделя 03 — State Model Review и чек-листы

## State Model Review — workflow

### Шаг 1. Носители состояния

**Must перечислить** всё, что наблюдает потребитель:

- values, requests, results, exceptions
- builders, registries
- lifecycle components
- configuration properties, effective configuration
- bean graph
- metrics, health, logs, actuator
- persistent/wire representations

### Шаг 2. Измерения и кардинальность

Для каждого носителя: поля, варианты, произведение boolean × enum × nullable.

### Шаг 3. Инварианты

Формат:

> Для любой `HttpExportConfiguration` endpoint абсолютный, timeout положителен, compression из поддерживаемого множества.

**Must указать:** владелец гарантии и момент установления.

### Шаг 4. Representable / valid / reachable

**Must найти:**

- представимые, но недопустимые
- допустимые, но недостижимые
- переходы в неопределённой фазе
- состояния только при race / partial failure

### Шаг 5. Transition table

| Из | Событие | В | Результат | Повтор |
|---|---|---|---|---|
| NEW | start | READY | runtime готов | запрещён/idempotent |
| READY | dependency lost | DEGRADED | ops по policy | — |
| DEGRADED | restored | READY | backlog возобновлён | — |
| READY | close | TERMINATED | ресурсы освобождены | idempotent |
| FAILED | close | TERMINATED | best-effort cleanup | idempotent |

**Must:** failures и concurrency, не только happy path.

### Шаг 6. Соответствие поверхностей

Java ↔ config ↔ beans ↔ health ↔ metrics ↔ logs ↔ migration.

### Шаг 7. Эволюция

Для enum, sealed, serialized form:

- добавление variant без поломки exhaustive consumers
- поведение старой версии при unknown wire value
- equality и canonicalization
- новый default меняет reachable state?
- новый transition делает terminal non-terminal?

## State Contract Map

| Поверхность | Состояния | Инварианты | Точка установления | Наблюдение | Проверка |
|---|---|---|---|---|---|
| Properties | disabled, http, in-memory | один mode | resolver после binding | startup diagnostics | binding tests |
| Runtime model | sealed variants | invalid unrepresentable | factories | typed DI | unit/property tests |
| Bean graph | absent / complete | no partial graph | auto-configuration | bean presence | ApplicationContextRunner |
| Lifecycle | new→ready→degraded→terminated | allowed transitions only | coordinator | API/events | transition/concurrency tests |
| Operations | ready/degraded/down | signals aligned | health/metrics | Actuator | operational tests |
| Evolution | known/unknown | predictable old behavior | parser/adapter | warning/error | compatibility fixtures |

## Тесты как доказательства границ

| Тип теста | Проверяет |
|---|---|
| constructor/factory | локальные инварианты |
| property-based | широкое пространство значений |
| decision-table | нормализация конфигурации |
| state transition | протокол lifecycle |
| concurrency | ordering, idempotency |
| context | bean graph |
| compatibility fixtures | старые representations |
| operational | health/metrics/logs согласованность |

### Property-based свойства (примеры)

- нормализация идемпотентна
- canonical equivalent inputs → равные runtime models
- любой успешно созданный value object проходит predicate
- после terminal transition нет обычного success
- encode/decode сохраняет known values

**Must:** negative space — для каждого инварианта способ доказать отклонение на правильной границе с диагностируемой ошибкой.

## Чек-лист: информационная структура

- [ ] Роль каждого типа ясна: value / entity / snapshot / view / handle / state / result?
- [ ] Product types — действительно независимые измерения?
- [ ] Sum type вместо nullable cluster?
- [ ] Equality соответствует идентичности понятия?
- [ ] Representation не опубликована случайно как semantic contract?

## Чек-лист: инварианты

- [ ] Инварианты — проверяемые утверждения?
- [ ] Владелец и этап установления для каждого?
- [ ] Нет повторных checks после нормализации?
- [ ] Межконтрактные инварианты: config + beans + ops?

## Чек-лист: состояния и переходы

- [ ] Representable / valid / reachable разделены?
- [ ] Transitions: precondition, effect, postcondition, failure?
- [ ] Terminal states, idempotency, повторные вызовы?
- [ ] Transitional states только при действии потребителя?
- [ ] Concurrency/cancellation не создают неописанных состояний?

## Чек-лист: отсутствие и ошибки

- [ ] `null` не объединяет несколько доменных смыслов?
- [ ] Optional — кардинальность, не universal result?
- [ ] Not found / unknown / not applicable / failed различены?
- [ ] Unknown external values — evolution policy?

## Чек-лист: Spring Boot

- [ ] Bindable properties отделены от runtime model?
- [ ] Четыре уровня валидности разделены?
- [ ] Defaults и aliases — deterministic policy?
- [ ] Нет случайного partial bean graph?
- [ ] Замена bean — explicit semantic contract?
- [ ] Runtime state согласован с health, readiness, metrics, logs?

## Контрольные вопросы (выборка)

1. Чем representable отличается от valid и reachable?
2. Почему три boolean = восемь комбинаций?
3. Когда record — value object, когда публикует лишнее representation?
4. Product vs скрытый sum type?
5. Почему subtype в sealed может быть incompatible change?
6. Какие смыслы скрывает `null`?
7. Snapshot vs live view?
8. Почему `validate()` не компенсирует свободно создаваемый invalid object?
9. Элементы полного контракта перехода?
10. Четыре уровня валидности Spring Boot?
11. Почему default — часть информационной модели?
12. State explosion и canonicalization?
13. Согласованность lifecycle и operational signals?

## Итоговые тезисы

1. Публичный тип = множество состояний.
2. Sum type устраняет nullable clusters.
3. State enum без transitions ≠ state machine.
4. Bindable config — слабая форма; runtime model — сильные гарантии.
5. Defaults, aliases, conditions, bean replacement меняют effective states.
6. Operational signals обязаны описывать ту же модель, что Java API.
7. Сильный API делает корректный путь естественным, invalid — непредставимым или немедленно диагностируемым.
