# Week 10 — Механика эволюции Java API

## Контекст

Эволюция Java API опирается на bytecode descriptors, symbolic references и JVM linking. Structural diff — необходим, но недостаточен.

## JVM linking — symbolic reference

Constant pool: binary name типа + имя member + descriptor.

```text
Methodref com/acme/Client.send:(Ljava/lang/String;)Lcom/acme/Result;
```

Переименование, изменение параметра или несовместимый return descriptor → символ больше не разрешается.

**Must:** проверять published class file, не только source diff.

## Classes

### Добавление members

Обычно binary-compatible для старых consumers. Риски:

- новый overload → source resolution
- новый instance method совпадает с method subclass
- новый constructor → выбор DI framework
- новое поле → serialization, reflection, equality
- static initializer → startup behavior

### Удаление и уменьшение accessibility

**Must-not:** удалять accessible type/member без evidence: never in supported API, usage inventory, major boundary, release train coordination.

«Метод не документирован» **не отменяет** effective API, если был доступен и использовался.

### final, abstract, sealed

| Изменение | Эффект |
|---|---|
| open → `final`/`sealed` | ломает external subclasses |
| concrete → `abstract` | ломает создание instances |
| снятие `final` | расширяет contract — появляются external subclasses |

## Methods и constructors

### Descriptor identity

```java
// v1: ()Ljava/util/ArrayList;
public ArrayList<String> values();
// v2: ()Ljava/util/List;  — descriptor изменился → binary break
```

**Must-not:** менять published descriptor без bridge method и считать совместимым.

### Covariant return

Compiler может генерировать bridge, но owner **не должен** рассчитывать на автоматическое сохранение descriptor.

### Constructors

- удаление public constructor → прямой break
- добавление constructor → reflection/DI selection
- изменение работы существующего → behavioral break (I/O, thread, validation, defaults)

## Overloads, lambdas, method references

```java
// v1
void submit(Callable<Result> task);
// v2
void submit(Supplier<Result> task);
// submit(() -> result) после recompilation → ambiguous
// старый bytecode продолжит работать → binary diff зелёный
```

**Must:** перед добавлением overload компилировать corpus call sites: literals/null, lambdas, method refs, generics, primitives/boxed, varargs, static imports, fluent chains.

**Must:** если semantics действительно отдельна — **новое имя** часто безопаснее ещё одного overload.

### null и most-specific method

Добавление более специфичного overload может изменить target для `null`, raw types, boxing, varargs → другой validation, exception, side effect.

## Parameters, return types, domains

### Type widening — не атомарно безопасно

- parameter type → более общий: меняет descriptor, ломает binary
- новый overload со старым методом: сохраняет descriptor, риски overload resolution
- return type более общий: уменьшает caller operations, меняет descriptor
- return type более конкретный: раскрывает implementation, сужает свободу

### Input/output domain

Тип `String` не определяет domain: допустимые значения, encoding, case, empty, max length, reserved patterns.

**Must:** проверять exhaustive handling при новом result variant, status code, subtype, enum constant.

Правило «consumer игнорирует неизвестное» работает только если было частью исходного contract.

## Exceptions и failure semantics

### Checked exceptions

Добавление checked exception в `throws` → source break. JVM descriptor не включает checked exceptions → binary может связываться.

### Unchecked exceptions — тоже контракт

Если документация рекомендует ловить `QuotaExceededException`, unchecked nature не делает type internal.

**Must:** для каждой failure category стабильны: meaning, retryability, partial success, cause preservation, error code, timing (sync/async/startup), cancellation/timeout.

### Усиление validation

Отказ от ранее принимаемых inputs — **behavioral breaking**, даже если считалось «нежелательным».

**Must:** risk classification, deprecation warning, validation mode или major boundary — не ссылка на отсутствие diff.

## Fields и constants

### Compile-time constant inlining

```java
public static final int DEFAULT_BATCH_SIZE = 100;  // v1
public static final int DEFAULT_BATCH_SIZE = 500;  // v2
// старый consumer может использовать 100 до recompilation
```

**Must:** для эволюционирующего значения — accessor или configuration policy, не `public static final`.

### Public fields

Public field публикует representation, mutability, synchronization. Замена property method позже не сохраняет old field reference.

## Generics, erasure, bridges

Source model и JVM model различаются. Изменение generic signature может не проявиться как linkage break, но влияет на compiler, reflection, inference, bridges.

### Изменение bounds

- усиление bound → source break
- ослабление → overload choice, provider assumptions
- порядок intersection bounds → erasure первого bound → binary descriptor

### Wildcards и variance

`List<T>` → `List<? extends T>` меняет read/write, inference, overriding. Оценивать corpus recompilation, не интуицией «PECS всегда совместим».

### Bridge methods

API diff tool **must** понимать synthetic/bridge: слепое исключение → пропуск binary dependency; слепое включение → шум.

## Inheritance и subclass compatibility

Protected surface = полноценный SPI: constructors, hook order, protected state, override, `super`, reentrancy, threading, method из constructor, template sequence.

### Fragile base class

Новый overridable method в superclass может совпасть с method external subclass → начинает участвовать в dynamic dispatch без намерения author.

**Must:** если subclassing не поддерживаемый use case — `final`, скрытые constructors, composition/strategy **до** первой публикации.

## Interfaces и default methods

| Добавление | Caller-facing (только library impl) | Open SPI |
|---|---|---|
| abstract method | может не затронуть callers | ломает implementors |
| default method | linking сохранён | semantic default критичен |

Риски default method: source conflict двух defaults, override method класса, method resolution после recompilation, неверная semantics, runtime exception, recursion.

**Conservative default must:** воспроизводит прежнее поведение, не заявляет capability, без скрытого I/O, не ослабляет security, bounded cost.

Если такого default нет — sibling capability interface честнее маскирующего default.

## Enum evolution

Добавление constant — JLS binary-compatible. Migration/behavioral breaks:

- exhaustive source switch не компилируется
- compiled switch получает неизвестный constant
- `Enum.valueOf` принимает новое имя
- ordinal-based persistence
- metrics cardinality
- сериализаторы запрещают unknown values

**Must-not:** публиковать ordinal как wire id. Нужен стабильный code/id и explicit unknown policy.

Удаление/переименование = delete + add; для data — alias/translation period.

## Records и sealed hierarchies

Record component меняет: canonical constructor, accessors, equals/hashCode, toString, deconstruction, reflection metadata, serialization shape.

**Must-not:** record для часто эvolving request с множеством optional fields — builder/options object даёт управляемый migration path.

Sealed: закрытие open hierarchy ломает external subtypes; добавление permitted subtype ломает exhaustive assumptions; pattern matching усиливает coupling.

## Annotations и annotation processing

- element с default → старые usages компилируются, но меняет runtime metadata и processor behavior
- element без default → source-invalid
- переименование element → break compiled values и source
- retention/target/repeatability → доступность compiler/reflection/framework

**Must:** processing rules (inherited, conflict resolution, order, generated names, incremental build, diagnostics) — часть contract.

Default value annotation element — structural addition, но semantics применяется ко **всем** существующим annotated elements после обновления → консервативная semantics.

## JPMS compatibility

| Изменение | Риск |
|---|---|
| удаление export/open | `IllegalAccessError`, reflection failure |
| добавление export | split-package/readability conflict |
| удаление `requires transitive` | consumer binary break |
| переименование module | `requires old.name` ломается |

**Must:** qualified `opens ... to framework.module` предпочтительнее blanket `open module`; список framework module names — compatibility surface.

## Java runtime baseline и MR-JAR

Повышение target class-file version → `UnsupportedClassVersionError`.

**Must:** `--release`, не только `-source/-target`. Policy отдельно: min runtime Java, compiler release, test JDK, vendor differences, срок поддержки baseline.

MR-JAR: versioned variants **must** сохранять единый public contract. Иначе behavior зависит от runtime Java как скрытый fork.

## Reflection, proxies, AOT

- binary names в configuration, `Class.forName`, service metadata, proxy, native hint — не видны signature diff
- JDK dynamic proxies: interface set, default dispatch, handler assumptions
- bytecode-generated subclasses: non-final classes/methods, accessible constructors, module opens
- AOT/native: reflection hints, proxy hints, resource paths — framework-facing contract

**Must:** compatibility matrix включает execution mode, если starter обещает AOT/native.

## Java object serialization

`Serializable` публикует stream contract: class name, hierarchy, fields, `serialVersionUID`, custom methods.

**Must:** явный `serialVersionUID` — фиксирует заявленную line, не делает несовместимые fields совместимыми.

Structural deserialization success ≠ behavioral compatibility (новый invariant требует `readObject`/proxy migration).

**Must-not:** Java serialization для long-lived storage и межсервисного протокола — предпочтителен versioned schema.

## Ключевые тезисы

1. Descriptor identity важнее source-совместимой на вид замены типа.
2. Additive overload — классический binary/source/behavioral разрыв.
3. Constant inlining создаёт два effective defaults в одном process.
4. Protected inheritance — высокая evolution cost.
5. Enum/sealed additions ломают exhaustive assumptions.
6. Record component публикует constructor, accessors, equality, data shape одновременно.
7. JPMS module name, exports, opens — контракт.
8. Reflection/proxy/AOT требуют отдельной runtime matrix.
