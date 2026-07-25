# Неделя 07 — Immutability, aliasing, collections

## Immutability как семантика

Immutability **должна** уменьшать temporal states: значение полностью определяется construction inputs.

**Сильна для:** identifiers, validated config snapshots, commands/events, policies, metadata, keys в maps/sets.

Immutable API **не обязан** быть record. Final class **может** скрывать representation и нормализовать данные.

## Deep immutability — checklist

**Must обеспечить:**
- отсутствие mutators
- окончательная инициализация
- отсутствие утечки mutable aliases
- immutable reachable objects **или** owned copies
- safe publication

Deep immutability — свойство **графа и контракта**, не modifier одного class.

## Final fields и safe publication

`final` стабилизирует **reference**, не contents:

```java
private final List<String> routes = new ArrayList<>(); // contents mutable
```

**Must в constructor/factory:**
- полностью установить invariants
- **не** публиковать `this` из constructor
- **не** регистрировать callback / запускать thread с `this` до завершения construction

Safe publication **шире** final: нужен happens-before edge (synchronized, volatile, concurrent collection, container, thread start).

## Aliasing и representation exposure

| Направление | Пример дефекта |
|---|---|
| inbound | constructor сохраняет caller-owned mutable input |
| outbound | getter возвращает internal mutable object |
| transitive | outer container copied, mutable elements shared |
| view-based | read-only wrapper делит backing state |
| callback-based | callback получает internal mutable context |

Review **должен** идти по object graph, не только по верхнему generic type.

## Alias как сознательный контракт

Sharing **допустим**, если явно документировано:
- какая память/state shared
- кто может писать
- как долго alias valid
- нужна ли synchronization
- что после close/recycle
- можно ли retain reference после callback

## Defensive copying

Owned immutable state **обычно** требует копии на входе **и** выходе.

```java
// Copy только outer array недостаточна — inner byte[] остаются aliases
```

**Must документировать:** глубину копии, null policy, normalization, performance contract.

**Must-not:** молча заменять snapshot на view ради optimization в minor release.

Trusted internal path (package-private constructor без повторной копии) **допустим**, если boundary закрыта.

## Collections

| API | Semantics | Когда использовать |
|---|---|---|
| `List.copyOf` | unmodifiable snapshot ссылок | elements immutable → strong boundary |
| `Collections.unmodifiableList` | read-only **live view** | сознательный shared observation |
| `ConcurrentHashMap` | thread-safe individual ops | **не** atomic compound invariant |

**Must-not** полагаться на check-then-act:

```java
if (!map.containsKey(key)) { map.put(key, createValue()); } // race
```

→ `computeIfAbsent` или domain operation `getOrCreate` с defined atomicity.

**Отдельно** фиксировать mutability keys **и** values.

## Arrays и ByteBuffer

Arrays **всегда** mutable. Return internal array = representation exposure.

**Must:** `clone()` / `Arrays.copyOf` для небольших values; отдельный type/name для zero-copy.

`ByteBuffer`:
- `duplicate()` — independent cursor, **shared** storage
- `slice()` — shared subsequence
- `asReadOnlyBuffer()` — запись запрещена через view, original **может** писать

«Read-only buffer» **≠** «immutable bytes». **Должны** быть определены: shared storage, cursor independence, lifetime, retain policy.

Reusable callback buffer = **scoped borrowing**. Documentation **must** запрещать retain или предлагать `copy()`/`detach()`.

## Builders и freeze

Builder lifecycle: `NEW → CONFIGURING → BUILT`

**Must решить** после `build()`:
- reusable / frozen / independent snapshots
- **не** связывать built objects с дальнейшими mutations builder (опасно без явного заявления)

`build()` **должен** передавать owned copies: `return new Policy(List.copyOf(rules));`

Functional updates (`withTimeout`) **должны** возвращать new instance; `setTimeout` для такой operation — неверное имя.

## Must / Must-not

**Must:**
- defensive copy на public boundary для owned immutable state
- `List.copyOf` / deep copy с учётом element mutability
- явные names для snapshot/view/copy
- review alias graph по всем edges

**Must-not:**
- считать unmodifiable wrapper defensive copy
- возвращать `byte[]` / internal array без clone или zero-copy contract
- допускать retain callback buffer без документации
- связывать built object с mutable builder state

## Анти-паттерны (кратко)

| Анти-паттерн | Почему опасно |
|---|---|
| `final` = immutable | mutable collection/elements |
| unmodifiable как copy | mutation через backing |
| getter → internal array | writable representation |
| shallow copy deep graph | transitive aliases |
| builder mutates built objects | скрытая coupling |

## Ключевые тезисы

1. Deep immutability = граф + publication, не `final`.
2. Aliasing review по object graph.
3. Defensive copy имеет depth и performance contract.
4. `List.copyOf` ≠ deep copy elements.
5. Arrays и buffers требуют explicit contract.
