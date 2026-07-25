# Неделя 06 — Тип как утверждение и выбор формы

## Контекст

Базовая линия: **Java 21** — records, sealed classes, pattern matching for `instanceof`/`switch`, record patterns. Preview features — **must not** как основа долгоживущего commercial API.

## Тип = множество значений + доказательство

**Must:** каждый public type приближает representable states к valid states.

```java
void schedule(PositiveDuration delay);  // proof at creation
void schedule(Duration delay);          // re-validate everywhere
```

**Must:** отдельный wrapper, когда различие повторяется, влияет на корректность, предотвращает правдоподобную ошибку.

**Must not:** wrapper «ради типобезопасности» без повторяющейся семантики.

## Nominal typing

Java: два класса с одинаковыми fields **must not** взаимозаменяемы без declared relation.

```java
record RequestId(String value) {}
record CorrelationId(String value) {}
```

**Must:** nominal distinction для identifiers, units, policies, lifecycle handles.

## Матрица выбора формы

| Форма | Главное утверждение | Сильная сторона | Главный риск |
|---|---|---|---|
| final class | скрытое representation, controlled behavior | evolution, invariants | boilerplate |
| open class | наследование implementation | framework reuse | fragile base class |
| interface | роль/capability, substitutability | SPI, polymorphism | случайная open extensibility |
| record | прозрачная полная state description | data modeling, equality | representation = API |
| enum | закрытые singleton values | finite set, switch | exhaustive consumers |
| sealed | закрытое семейство variants | sum types, patterns | adding variant breaks exhaustiveness |
| annotation | metadata | declarative integration | слабая static guarantee |

## Вопросы перед выбором (must)

- потребитель наблюдает data или capability?
- variants closed или extensible?
- representation должна быть прозрачна?
- identity vs structural equality?
- consumer implements или only receives values?
- pattern matching vs dynamic dispatch?
- evolution без major release?

## Final class vs record

**Must final class, когда:**

- скрытое/изменяемое representation
- нетривиальные invariants
- API must not promise component-wise access
- representation evolves independently от понятия

**Must record, когда:**

- components = полная стабильная контрактная state description
- immutable messages, specs, snapshots, keys

## Open class

**Must not:** open class по умолчанию.

**Must open only if:**

- framework template method с controlled lifecycle
- subclass = supported extension
- invariants устойчивы к overrides
- narrow documented hooks + contract tests
- готовность поддерживать protected surface

**Must prefer:** final class + composition или interface SPI.

### Abstract class vs interface

- abstract class: shared state + protected lifecycle for extensions
- interface: role without imposed representation; multiple classification

Default methods **must not** считать equivalent instance state.

## Interface roles

### Consumer-facing

**Must not:** выглядеть как SPI, если implementation не поддерживается.

Use: documentation, package/module boundaries, sealed interface, annotations.

### SPI

**Must document:** callable methods, concurrency, lifecycle, exceptions, optional capabilities, compatibility on new methods.

**Must:** SPI stability > consumer-only abstraction.

### Interface Segregation

**Must not:** god interface **or** десятки one-method roles без independent capabilities.

## Default methods

**Must:** default выражается через existing contract (`isEmpty()` from `size()`).

**Must not:** default требует new state/lifecycle/policy interface не обещал.

**Must not:** `UnsupportedOperationException` default для нового API — use capability interface or explicit result.

## Быстрый decision tree

```text
variants mutually exclusive?
  yes → sealed hierarchy / enum
  no → product record/class with validated fields

consumer implements type?
  yes → interface SPI (+ contract tests)
  no  → final class / record / sealed (consumer reads)

representation must evolve independently?
  yes → final class + factories
  no  → record if stable full state description

operations stable, implementations grow?
  yes → interface + dynamic dispatch
  no  → sealed + pattern switch
```

## Чек-лист выбора формы

- [ ] Semantic role одна и ясна?
- [ ] Representable states близки к valid?
- [ ] Extensibility direction (author/consumer/extension) решена?
- [ ] Equality semantics соответствует role?
- [ ] Evolution scenario для add field/variant/operation оценён?
- [ ] Public type не протекает vendor/framework без deliberate contract?

## Key theses

1. Public type = values + operations + evolution contract.
2. Nominal types различают одинаковые representations.
3. Final class — representation independence; record — transparent state.
4. Open class/SPI — commitment поддерживать external implementations.
5. Default method — binary evolution aid, not semantic guarantee.
6. Sealed/enum — evolution decision, не синтаксическое удобство.
7. Type form must follow information model, not boilerplate reduction.
