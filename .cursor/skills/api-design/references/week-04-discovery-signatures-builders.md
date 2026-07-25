# Неделя 04 — Discoverability, naming, сигнатуры, builders

## Discoverability

**Must not:** путать discoverability с размером public surface.

### Must для discoverability

- узнаваемый artifact и package naming
- одна каноническая entry point для основного сценария
- термины совпадают с задачей пользователя
- IDE completion: общее → конcretное
- examples на supported API
- нет конкурирующих «главных» фасадов

### Signal-to-noise

**Must:** уменьшать effective public surface: package boundaries, module exports, internal naming, sealed types, явные entry points.

**Must not:** `public` из технической необходимости выглядит как recommended path.

### Recognition over recall

**Must prefer:** enum, typed factory, configuration metadata, contextual IDE suggestions.

**Must not:** string keys, FQCN в YAML, hidden bean names как primary path.

```java
// Must prefer
RetryPolicy.exponential(initialDelay, maxDelay)

// Must not as primary without schema/diagnostics
RetryPolicy.of("exp", Map.of("initial", "100", "cap", "5000"))
```

## Naming как навигация

**Must:** имя прогнозирует receiver, input, effect, result.

### Пары операций — must not синонимы при разном поведении

| Пара | Различие |
|---|---|
| create / get / find / require | создание vs lookup vs обязательность |
| validate / normalize / resolve | проверка vs канонизация vs разрешение |
| start / enable / resume | lifecycle semantics |
| stop / close / shutdown / terminate | освобождение vs завершение |
| send / enqueue / publish / export | sync vs async vs channel |
| status / snapshot / view / result | temporal semantics |

### Positive naming

**Must not:**

```java
disableFallback(false)  // двойное отрицание
```

**Must:**

```java
fallback(FallbackPolicy.ENABLED)
```

Enum ENABLED/DISABLED — не всегда лучше boolean; польза при видимости в вызове и дополнительных вариантах.

## Сигнатуры и параметры

**Must not:** два параметра одного типа без защиты:

```java
connect(Duration timeout, Duration idleTimeout);  // error-prone
```

**Must:** value types, builder, parameter object, static factories — если ошибка правдоподобна и существенна.

### Порядок параметров (must быть согласован)

- главный operand → modifiers
- обязательное → необязательное
- input → policy/context
- temporal constraints рядом
- одинаковый порядок во всех overloads

## Overloads

**Must:** overloads = одна операция с разной полнотой ввода; согласованные defaults, exceptions, lifecycle.

**Must not:** одинаковое имя для разных semantic operations (immediate export vs buffered span).

### Null и lambdas

**Must:** отдельные имена при семантическом различии:

```java
fromSnapshot(snapshot);
fromRemoteParent(parent);
```

**Must not:** combinatorial overloads — каждый optional удваивает число overloads; для modes — factories/sum types.

## Builders

Builder **must** решать: много optional params, named-style, immutable result, межполевые invariants, blueprint reuse.

**Must not:** builder как автоматический признак удобства; builder не исправляет sum type, отсутствие domain types, неопределённый lifecycle.

### Required values

**Must:**

```java
ExportPipeline.builder(SpanExporter exporter, ExportQueue queue)
        .batchSize(256)
        .build();
```

**Must not:** пустой `builder()` с обязательными полями только в `build()`.

### Builder contract (must документировать)

- mutable или persistent
- reuse после `build()`
- last-write-wins vs duplicate error
- зависимость смысла от предыдущего вызова
- момент validation
- thread safety
- ссылки на mutable inputs

### Staged builder

**Must использовать:** короткий стабильный протокол, высокая цена sequencing error.

**Must not:** для обычной конфигурации — extraneous load, generic errors, evolution cost.

**Must not:** staged builder только чтобы `build()` никогда не бросал validation exception.

## Parameter object

**Must:** когда параметры образуют понятие с инвариантами и переиспользуются (`ExportRequest`).

**Must not:** `Options` с 20 nullable полями — options bag.

## Convention over configuration

**Must для каждой convention:**

- правило активации
- способ увидеть результат
- способ переопределить
- conflict policy
- failure message при неоднозначности

**Must:** явность на границе риска: удаление данных, external publish, security, blocking, ownership threads, production mutation — explicit opt-in или сильный convention contract.

## Documentation layers

| Слой | Must содержать |
|---|---|
| Tutorial | первый безопасный успех |
| How-to | задача + troubleshooting |
| Reference | values, defaults, states, errors, compatibility |
| Conceptual | связь слоёв, ментальная модель |

**Must not:** один README на все роли; docs не компенсируют опасный default в advanced section.

**Must:** examples компилируются в CI — исполняемые обещания.
