# Неделя 05 — Constructor, factory, record

## Public constructor

### Must использовать, когда

- класс = создаваемое понятие без скрытого выбора implementation
- одна основная форма
- мало обязательных аргументов
- порядок и роли очевидны
- нет surprising side effects
- инварианты атомарны при создании
- caching / polymorphic return не нужны

### Сильные стороны

- однозначно создаёт экземпляр объявленного класса
- хорошая discoverability в IDE
- DI/framework support
- final fields
- естественен для value object

### Must not / ограничения

- не выражает variant (имя = класс)
- всегда новый identity (не cached)
- плохо различает одинаковые типы параметров
- публикует representation в source/binary contract
- затрудняет evolution при новом обязательном component

```java
new Endpoint(String, String, int)  // must not: роли неясны
```

## Telescoping constructors

**Must not:** неограниченный рост overloads с hidden defaults.

**Must (если 2–3 overload):** одна операция, делегирование канонической форме, одинаковые defaults.

**Must not:** ложная симметрия — разные modes под одним типом параметра:

```java
Exporter(URI endpoint)
Exporter(String inMemoryChannel)  // must → named factories
```

## Static factory methods

### Словарь имён (must быть последовательным)

| Имя | Ожидание |
|---|---|
| `of` | прямая композиция компонентов |
| `from` | преобразование representation без потери смысла |
| `parse` | разбор external text/binary; format errors |
| `copyOf` | независимая/canonical копия |
| `valueOf` | canonicalization (JDK style) |
| `create` | общее создание |
| `open` | создание + acquire ресурса |
| `load` | I/O / external source |
| `newInstance` | новый identity важен |
| `getInstance` | shared/cached возможен |

### Must использовать factory для

- named variants
- conversion / normalization
- subtype / hidden implementation
- cached/interned instance
- несколько имён при одинаковых parameter types

### Must not

- каталог factories в одном `PlatformObjects`
- factory возвращает interface, но consumer needs cast to implementation-specific ops

## Validation timing и failure model

| Граница | Must проверять |
|---|---|
| Value object | локальные invariants в constructor/factory |
| Межполевые | canonical factory или `build()` |
| Графовые | runtime factory / container |
| Средовые | `open`/`start`, health, startup policy |

**Must not:** factory обещает ready runtime после только синтаксической проверки.

### Programming error vs external input

```java
TenantId of(String canonical);           // IllegalArgumentException
ParseResult<TenantId> parse(String input); // explicit failure branch
```

**Must not:** механический `tryCreate` для всех constructors.

### Atomic failure

**Must:** при failure — cleanup hooks, threads, temp files; rollback discipline.

**Must not:** partial side effects без моделирования в result type.

## Records

### Must использовать record, когда

- components = полная стабильная state description контракта
- immutable messages, specs, snapshots, keys, value aggregates

### Compact constructor must

- validation локальных invariants
- normalization → documented equality semantics
- `List.copyOf` / defensive copy для mutable components

**Must not:** record только ради boilerplate при unstable/internal components.

### Shallow immutability

**Must:** defensive copy на входе; оценить mutability elements.

**Must not:** полагать, что `List.copyOf` = deep immutability.

### Evolution

Добавление component — **major change** (constructor, accessors, equality, patterns, serialization).

**Must:** private final class + factories, если representation должна эволюционировать независимо.

## JavaBeans / setter creation

**Must:** только как bindable external boundary.

```text
mutable properties → validate → normalize → immutable runtime model
```

**Must not:** тот же JavaBean как dependency runtime components.

Если mutable creation public — **must** определить: повторное присваивание, last-write-wins, validation timing, thread safety, equality при mutation.

## I/O в constructor

**Must not:** network/blocking I/O в constructor без явной причины.

**Must:** `open`, `connect`, `load`, async factory для resource acquisition.

## Lazy vs ready client

**Must not:** `build()` обещает ready client, если connect на первом request.

**Must:** явный lazy contract **или** отдельная `connect`/`start`.
