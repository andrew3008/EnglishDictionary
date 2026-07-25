# Неделя 07 — Единый контракт состояния

## Контекст

Mutability, ownership и lifecycle — **один контракт управления состоянием**. Java не кодирует владение в типах; контракт проектируется через типы, имена, factories, документацию и runtime checks.

## Три обязательных вопроса

Для каждого публичного object **должен** быть ответ:

| Вопрос | Что фиксировать |
|---|---|
| **Mutability** | Какие наблюдаемые свойства могут измениться и кем |
| **Ownership** | Кто контролирует изменения, sharing и cleanup |
| **Lifecycle** | Фазы существования и допустимые операции в каждой |

**Не выводите** один ответ из другого: immutable object может требовать `close()`; unmodifiable view может наблюдать mutation backing state; Spring singleton scope **не доказывает** thread safety.

## Наблюдаемое состояние

Контракт относится не только к fields. **Должны** учитываться:

- результаты methods, iteration order, equality/hashCode
- внешние ресурсы (файл, сокет, transaction, remote session)
- callbacks, metrics, health, способность принять новую работу
- cursor position, cache latency, terminal state после `close()`

Object **может** быть mutable без setters, если `next()`, `read()` или `close()` меняют наблюдаемое поведение.

## Порядок проектирования

1. **Сначала** семантика (mutability, ownership, lifecycle)
2. **Затем** механизм (copy, lock, state enum, `AutoCloseable`, container callback)

Механизм без контракта создаёт ложные гарантии. `ConcurrentHashMap` **не определяет** domain invariant между несколькими keys.

## Таксономия mutability

| Класс | Суть | Опасность |
|---|---|---|
| **Mutable** | Наблюдаемое изменение после construction | Нужны mutators, visibility, concurrent access |
| **Unmodifiable view** | Mutation через view запрещена | Backing state **может** меняться через alias |
| **Shallow immutable** | Поля final, elements mutable | Record с `List<T>` **не доказывает** immutability |
| **Deep immutable** | Весь object graph неизменяем | Требует aliases, elements, safe publication |
| **Effective immutable** | Freeze после init внутри impl | **Слаб** для public API — consumer не видит момент freeze |

## Режимы ownership

| Режим | Изменение | Cleanup | Пример |
|---|---|---|---|
| exclusive owner | один владелец | владелец | file handle |
| transferred ownership | передаётся | новый владелец | `adopt(channel)` |
| borrowed reference | владелец прежний | borrower **не закрывает** | injected `Executor` |
| shared immutable | нет mutation | обычно нет | config snapshot |
| shared coordinated | по protocol | coordinator | concurrent registry |
| scoped capability | ограниченное время | scope/controller | callback context |

**Должна** быть стабильная vocabulary по всей библиотеке. `register(X)` **не должен** в одном месте копировать, в другом — сохранять alias.

## Ownership параметров

| Режим | Правило | Именование |
|---|---|---|
| call-only borrowing | reference **не сохраняется** после вызова | — |
| retained borrowing | reference сохранена, owner — caller | документировать lifetime и mutation |
| ownership transfer | после success caller **не использует** source | `adopt`, явная документация |
| copy-on-entry | factory независима после return | `copyOf`, не `of` |

**Должен** быть определён failure path при transfer: переходит ли ownership, если validation/construction завершились исключением.

## Ownership возвращаемых значений

| Форма | Caller обязан | Type hint |
|---|---|---|
| owned result | cleanup (try-with-resources) | `AutoCloseable` в declared type |
| borrowed result | **не закрывать** | узкая capability (`Executor`, не `ExecutorService`) |
| snapshot | treat as point-in-time | `snapshot`, immutable return type |
| live view | учитывать future mutation | `view`, `liveMetrics` |
| cursor/stream | ограниченный lifetime | `openCursor`, example с try-with-resources |

Слово `get` **не сообщает** ни один из contracts.

## Snapshot vs live view vs cursor

| Форма | Consistency | Именование | Риск |
|---|---|---|---|
| Snapshot | logical moment | `snapshot()` | атомарность capture **должна** быть явной |
| Live view | отражает будущие изменения | `view()`, `liveMetrics()` | iteration races, temporal coupling |
| Cursor | собственный mutable position | `openCursor()` | limited lifetime, I/O resource |

## Workflow: Contract before mechanism

```
1. Классифицировать mutability (deep/shallow/view/confined)
2. Назначить owner каждой resource edge (in/out/delegate)
3. Выбрать snapshot | view | copy | cursor для return collections
4. Нарисовать lifecycle state machine
5. Только потом — lock, copy, AutoCloseable, Spring callback
```

## Must / Must-not

**Must:**
- классифицировать каждый public stateful type
- назначать owner **per resource edge**, не «object в целом»
- выражать ownership через names (`copyOf`, `borrow`, `open`, `adopt`)
- определять failure path при ownership transfer
- различать snapshot, view, copy, cursor в names и docs

**Must-not:**
- считать `final` fields доказательством immutability
- использовать `Collections.unmodifiableList` как универсальную defensive copy
- возвращать internal array/buffer без explicit zero-copy contract
- полагаться на `get` для semantics sharing
- менять snapshot→view или borrowed→owned в minor release без migration

## Resource owner vs data owner

Object **может** владеть данными, но заимствовать dependency. Client копирует request metadata, но **borrow** `Executor`. `close()` client **must** остановить свои tasks, **must-not** закрывать shared executor.

Ownership анализируется **per outgoing edge** object graph, не «object целиком».

## Provider/Supplier ambiguity

`Provider<T>` / `Supplier<T>` **не сообщает:**
- shared singleton vs new caller-owned resource
- pooled borrowed handle vs cached value
- context-scoped instance

Для resource-bearing values **must** использовать domain names: `open`, `acquire`, `borrow` + release protocol.

Pool **must** return lease/handle, не raw pooled object:

```java
try (ConnectionLease lease = pool.acquire()) {
    lease.connection().execute(...);
}
```

## Decorator ownership

**Must** явно: owning wrapper (closes delegate) vs non-owning view. Ambiguous `new Wrapper(delegate)` **must-not** скрывать противоположные policies.

## Ключевые тезисы

1. Mutability, ownership, lifecycle — единый контракт.
2. Наблюдаемое состояние шире fields.
3. Java type system не кодирует ownership — names и docs обязательны.
4. Unmodifiable ≠ immutable; record ≠ deep immutable.
5. Transfer contract **обязан** определять failure path.
6. Ownership per resource edge, не per object.
7. Pool lease лучше raw resource return.
