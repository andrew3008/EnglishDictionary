# Неделя 03 — Product types, sum types и отсутствие

## Product type (произведение)

Record/class с несколькими полями утверждает: компоненты **существуют одновременно** и комбинируются.

**Must использовать product type, если:** каждое поле имеет смысл при любом допустимом значении остальных; межполевые ограничения понятны и устойчивы.

**Must not:** публиковать product, когда поля взаимозависимы или относятся к разным режимам.

### Тест review

> Если изменить одно поле, остаётся ли смысл остальных неизменным?

Если нет — перед вами **скрытый sum type**, не независимые измерения.

### Boolean blindness

```java
// Must not: 8 representable, ~3 valid
record RetryOptions(boolean enabled, boolean exponentialBackoff, boolean useServerHint) {}

// Must: именованные режимы
sealed interface RetryPolicy {
    record Disabled() implements RetryPolicy {}
    record Fixed(Duration delay) implements RetryPolicy {}
    record Exponential(Duration initial, Duration max) implements RetryPolicy {}
    record ServerDirected(Duration fallback) implements RetryPolicy {}
}
```

**Must not:** `publish(event, true, false)` — boolean без имени режима.

## Sum type (сумма)

Sealed interface/class выражает **один из** взаимоисключающих вариантов с разными данными и инвариантами.

**Must использовать sealed hierarchy, если:**

- варианты взаимоисключающие
- несут разные данные / инварианты
- нужен исчерпывающий разбор
- эволюция — один концепт

**Must not:** прятать variant-specific data в nullable cluster («если X задан, Y должен отсутствовать» в документации).

## Enum vs sealed hierarchy

| Форма | Когда |
|---|---|
| Enum | вариант без собственных данных; поведение одинаково параметризуется |
| Sealed + records | разная структура, разные операции, variant-specific payload |

**Must not:** превращать каждый enum в hierarchy «на будущее»; прятать data в соседних nullable-полях ради сохранения enum.

## Цена закрытого множества (sealed)

Перед публикацией **must** решить:

- множество вариантов концептуально закрыто или только известно сегодня
- потребитель реализует интерфейс или только читает значения
- допустима ли стратегия `Unknown`/`Other`
- новый variant — minor или major change
- где compatibility boundary producer/consumer

**Must:** закрытость — часть compatibility contract, не просто модификатор Java.

## Record ≠ value object автоматически

Record даёт: immutability компонентов, equality, компактный синтаксис.

**Must not полагаться на record для:**

- доменной валидности (нужен compact constructor / factory)
- защиты от mutable components
- нормализации и canonical equality
- скрытия representation
- совместимости с будущим расширением формы

**Must:** record, когда полная форма **действительно** контракт; иначе final class + factory.

## Null, Optional, unknown, failure — разные состояния

**Must not:** объединять в `null`: отсутствие, неизвестность, неприменимость, ещё не вычислено, ошибка загрузки, намеренное удаление.

| Модель | Выражает |
|---|---|
| `Optional.empty()` | zero-or-one; причина отсутствия неизвестна |
| `sealed TimeoutSetting` | inherit / disabled / explicit |
| `Known` / `Unknown(wireValue)` | forward compatibility |
| `Found` / `NotFound` / `Failed` | lookup vs failure vs unavailable |

### Optional — ограничения

**Must использовать Optional для:** результата поиска (найдено 0 или 1).

**Must not использовать Optional для:**

- полей configuration properties
- serializable DTO
- параметра метода (два API в одном)
- коллекций
- представления ошибки

## Catch-all states

| Константа | Смысл |
|---|---|
| `UNKNOWN` | наблюдатель не знает |
| `OTHER` | известное значение вне классификации |
| `CUSTOM` | пользовательский вариант с явной конфигурацией |

**Must not:** один catch-all — разрушает диагностику и evolution semantics.

## Stringly typed state

**Must:** string для mode/status/outcome, если множество **открыто** и управляется внешним namespace — определить grammar, case, normalization, unknown policy.

**Must not:** string для закрытого конечного множества — enum/sealed/value type.

## Invalid object + validate()

**Must not:** публичный объект свободно создаётся некорректным с обязанностью «не забыть validate()».

**Must:** `validate()` на mutable/bindable форме → после неё **более сильная модель**, не тот же сомнительный объект.

## Алгебраическая оценка state explosion

```java
// Product: boolean × optional × optional → много invalid
record Config(boolean enabled, URI endpoint, String channel) {}

// Sum: 3 valid forms
sealed interface Config {
    record Disabled() implements Config {}
    record Http(URI endpoint) implements Config {}
    record InMemory(String channel) implements Config {}
}
```

**Must при state explosion:**

1. перечислить измерения
2. определить различимое поведение
3. объединить эквивалентные комбинации
4. запретить противоречивые
5. дать имена режимам
6. нормализовать в конечную модель

## Decision table (must существовать при проектировании)

| enabled | mode | endpoint | Результат |
|---|---|---|---|
| false | * | * | Disabled |
| false | http | задан | **Ошибка:** противоречие |
| true | http | задан | Http |
| true | http | отсутствует | **Ошибка:** endpoint обязателен |
| true | in-memory | * | InMemory(default channel) |

## Анти-пatterns (must not)

- Nullable field cluster вместо sum type
- Setter protocol через некорректные промежуточные состояния без изоляции builder
- Product type для mutually exclusive transport/mode fields
- `validate()` как компенсация свободно создаваемого runtime object
