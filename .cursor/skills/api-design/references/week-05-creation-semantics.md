# Неделя 05 — Семантика создания и классификация объектов

## Контекст

Форма создания — первая операция и **семантическая часть контракта**, не синтаксическое предпочтение.

**Must not:** выбирать pattern по числу параметров («4 параметра → builder») без анализа семантики.

## Что обещает успешное создание

**Must гарантировать после успеха (явно):**

- структурная полнота (на уровне этапа)
- локальные инварианты выполнены
- объект безопасно публиковать (если заявлено)
- разрешён заявленный набор операций
- ownership входных данных определён
- failure не оставил наблюдаемый частичный объект

**Must not:** `new` + обязательный `validate()` + `initialize()` + `register()` под именем итогового типа без явного `Draft`/`Builder`/`UninitializedRuntime`.

## Creation form публикует модель

| Форма | Утверждение |
|---|---|
| Constructor | одна форма с N независимыми компонентами |
| Named factory | именованный variant (`exponential`) |
| Builder | сборка из решений, возможно произвольный порядок |

**Must:** форма соответствует истинной структуре понятия.

## Создание и lifecycle

```text
description/configuration → construction → activation → use → shutdown
```

**Must разделять**, когда construction включает threads/sockets/registration/native resources.

**Must not:** искусственное `new`/`init`/`start` без реального протокола — лишние недопустимые состояния.

## Классификация создаваемых объектов

| Категория | Типичная форма | Главный риск |
|---|---|---|
| Value | constructor / static factory / record | недопустимое значение |
| Specification | factory / builder | скрытые defaults, mutable aliasing |
| Runtime service | factory / DI / container | lifecycle, утечка graph |
| Resource handle | `acquire` / `start` / `subscribe` | неясное ownership/close |
| SPI implementation | provider / container | нестабильный construction protocol |

### Value object

**Must:** immutable, valid сразу после создания.

Примеры: `TenantId`, `RetryBudget`, `SpanName`, `ExportDeadline`.

### Specification / configuration value

Описывает будущую операцию/runtime; **must not** путать с mutable configuration session.

**Must:** готовая spec — собственные invariants, equality, ownership.

### Runtime service

Identity, dependencies, concurrency, lifecycle.

**Must not:** public constructor, раскрывающий internal graph и обход instrumentation.

### Resource handle

**Must:** creation как доменная операция, не constructor.

Примеры контракта: `SpanExecution start(SpanSpec)`, `Lease acquire(...)`, `Subscription subscribe(...)`.

**Must define:** close/shutdown responsibility, idempotency close, behavior if not closed.

### Extension implementation

Constructor requirements provider → часть extensibility contract.

**Must:** no-arg или documented framework creation path; contract tests for providers.

## Anti-patterns создания (must not)

| Anti-pattern | Проблема |
|---|---|
| `new` + `init()` + `start()` без типов фаз | недопустимые промежуточные состояния |
| Constructor с network I/O | blocking, weak failure model |
| `build()` обещает ready, connect lazy | ложный lifecycle contract |
| Один `PlatformObjects` factory catalog | потеря capability boundaries |
| JavaBean как runtime dependency | слабые guarantees по всему коду |

## Вопросы перед выбором формы (must)

1. Что создаётся: value / spec / runtime / handle / provider?
2. Одна форма или несколько variants?
3. Сколько required inputs; роли очевидны?
4. Optional независимы или modes?
5. Когда установлены все инварианты?
6. Creation чистое или захватывает ресурсы?
7. Polymorphic return, caching, hiding implementation?
8. Injectable creation mechanism?
9. Ownership inputs и результата?
10. Evolution path?

**Must document** ответы в Creation Contract Matrix при review стартера или библиотеки.

## Сравнение форм (краткая матрица)

| Форма | Публикует | Must not использовать для |
|---|---|---|
| Public constructor | одну каноническую форму | variants, I/O, hidden impl choice |
| Static factory | variant, conversion, caching | замены DI для runtime graph |
| Builder | optional assembly, blueprint | mutually exclusive modes |
| Factory object | context-dependent creation | простого value с 2 полями |
| Registry/SPI | open implementation set | closed domain с 3 variants |
| Container `@Bean` | platform-managed capability | app-facing manual graph assembly |

## Чек-лист быстрой проверки

- [ ] После `new`/`build()`/`create()` понятен уровень готовности?
- [ ] Нет скрытого `validate()`/`init()` для итогового типа?
- [ ] Creation side effects видны из имени (`open`, `connect`, `load`)?
- [ ] Specification переиспользуется без mutable aliasing?
- [ ] Handle создаётся операцией, не constructor?
- [ ] SPI creation protocol документирован и тестируем?

## Key theses

1. Creation form = семантика, не краткость.
2. После успеха must быть понятен уровень готовности.
3. Constructor оптимален для одной канонической прозрачной формы.
4. Blueprint ≠ live object — must not смешивать в naming/health.
5. Framework-shaped object must локализовать на adapter boundary.
6. Несколько entry points к одному type — must сверить defaults и guarantees.
7. Creation failure must not оставлять partial observable state.

## Связь с другими неделями

- **Неделя 03:** product/sum types, representable vs valid — выбор формы создания must следовать информационной модели.
- **Неделя 04:** required inputs visible early — usability creation entry points.
- **Неделя 06:** record/sealed/final class — типовая форма создаваемого значения.

См. также: `week-05-constructor-factory-record.md`, `week-05-builder-factory-spi.md`, `week-05-spring-creation-pipeline.md`, `week-05-creation-review-checklists.md`.
