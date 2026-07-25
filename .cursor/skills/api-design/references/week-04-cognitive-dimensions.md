# Неделя 04 — Cognitive Dimensions

## Назначение

Cognitive Dimensions — язык для обсуждения мыслительной работы, которую навязывает notation/API. **Must** использовать вместо «мне неудобно».

## Viscosity (сопротивление изменению)

Сколько мест изменить для одного намерения?

| Вид | Проявление |
|---|---|
| Repetition | одно изменение повторяется в builder, enum, YAML, condition, docs, fixtures |
| Scope | трудно ограничить область эффекта |
| Migration | переход требует временно противоречивой системы |

**Must снижать:** единый source of truth, композиция, stable extension points, локальная конфигурация.

**Must not:** слишком динамический registry ценой type safety и discoverability.

## Hidden dependencies

Корректность элемента зависит от другого, но связь не видна в используемой форме.

**Must явно выражать:**

- порядок вызовов builder
- bean по строковому name
- property только при optional dependency
- callback на executor с неизвестным lifecycle
- default меняет поведение другого starter

**Must:** constructor injection, typed references, configuration metadata, condition report → наблюдаемая связь.

## Premature commitment

Решение до появления достаточной информации.

**Must not:** factory требует serializer до transport; starter требует bean override до знания name/ordering.

**Must not:** полагаться, что builder с validation только в `build()` устраняет premature commitment — лишь откладывает ошибку.

## Hard mental operations

Вычисления «в голове», которые API **must** перенести в типы:

- комбинация boolean flags
- единицы `long timeout`
- effective precedence нескольких defaults
- интерпретация generic compilation error
- двойное отрицание `disableFallback=false`

**Must:** typed units, positive naming, decision tables, normalized effective configuration, ограниченные варианты.

## Role-expressiveness

**Must not:** `Handler`, `Manager`, `Context`, `Config`, `Processor` без роли (policy? state? factory? result?).

**Must:** role-expressive сигнатуры:

```java
ExportResult export(SpanBatch batch, ExportDeadline deadline);
```

## Visibility и juxtaposability

**Must:** потребитель видит нужные компоненты без поиска по internal packages.

**Must обеспечить:**

- группировку packages
- близость overloads и factories
- варианты в одной таблице docs
- IDE completion от общего к конкретному
- actuator с effective values
- diagnostics с condition и outcome

**Must not:** собирать effective config из YAML + env + defaults + condition log вручную.

## Error-proneness

Небольшая ошибка → правдоподобный, но неверный результат.

**Must not:**

- `int` milliseconds без единиц
- два соседних `String`
- overloads с `null`
- порядок boolean parameters
- mutable shared builder

**Must:** value types с ролями; type safety не заменяет role naming для двух `Duration`.

## Progressive evaluation

Проверка частичного результата до завершения задачи.

**Must предоставлять где уместно:**

- validation конфигурации до runtime
- `build()` с локальной диагностикой
- dry-run / effective configuration report
- test slice стартера
- preview compiled route table
- health/readiness после init

**Must:** чем позже ошибка — тем больше контекста должен восстановить потребитель.

## Применение к review

Для каждого top task **must** отметить evidence по восьми измерениям:

| Dimension | Вопрос |
|---|---|
| Viscosity | Сколько файлов/поверхностей для одного изменения? |
| Hidden deps | Какие связи не видны в форме использования? |
| Premature commitment | Где решение до информации? |
| Hard mental ops | Что считается в голове? |
| Role-expressiveness | Понятна ли роль каждого элемента? |
| Visibility | Нужен ли поиск по internal? |
| Error-proneness | Легко ли перепутать параметры? |
| Progressive eval | Когда обнаруживается ошибка? |

## Usability budget (сравнение вариантов)

| Показатель | Вопрос |
|---|---|
| Entry points | Сколько кандидатов выглядят главной точкой? |
| Required concepts | Сколько понятий до первого корректного вызова? |
| Decisions | Сколько выборов без default? |
| Hidden decisions | Сколько решает система неявно? |
| Feedback latency | Когда узнаёт об ошибке? |
| Recovery distance | Шагов от ошибки до места исправления? |

Числа — не универсальные нормы; **must** для сравнения вариантов и регрессий.
