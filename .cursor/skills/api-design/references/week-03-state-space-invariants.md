# Неделя 03 — Пространство состояний и инварианты

## Контекст

Семантика API проверяема только когда известны: какие данные существуют, какие комбинации допустимы и какие переходы разрешены. Информационная модель — не перечень полей DTO, а система различий, связей, ограничений и состояний.

Для Spring Boot-стартера состояние включает: property sources, binding, defaults, validation, conditions, bean graph, порядок инициализации, доступность внешних ресурсов.

## Три множества состояний

| Множество | Смысл | Вопрос review |
|---|---|---|
| Representable | Всё, что позволяет создать публичная форма | Какие комбинации допускают типы и сигнатуры? |
| Valid | Состояния, удовлетворяющие инвариантам | Какие представимые значения имеют смысл? |
| Reachable | Достижимые через поддерживаемые переходы | Может ли корректный сценарий реально привести сюда? |

**Must:** приближать representable, valid и reachable друг к другу; расхождение — осознанное и локализованное.

**Must not:** анализировать только valid и игнорировать representable (public constructor, Jackson, reflection, Spring Binder создадут «невозможное»); публиковать valid, недостижимое без reflection.

## Инвариант — ядро контракта

Инвариант — проверяемое утверждение, истинное во **всех наблюдаемых допустимых** состояниях.

**Must:** формулировать как предикат над состоянием, не как инструкцию потребителю.

| Класс | Область | Пример |
|---|---|---|
| Локальный | одно значение | `timeout > 0` |
| Структурный | связь полей | `min <= max` |
| Агрегатный | несколько объектов | route names уникальны |
| Временной | порядок событий | `start` предшествует `stop` |
| Ресурсный | владение | lease освобождается один раз |
| Наблюдаемый | внешние сигналы | terminal result публикуется ≤ 1 раза |
| Межконтрактный | Java + config + Spring + ops | disabled capability не создаёт bean и misleading health |

## Конвейер усиления гарантий

```text
raw input → bound representation → validated input → normalized model → runtime graph
```

| Этап | Гарантия |
|---|---|
| Raw input | наличие текста/значения из внешнего источника |
| Bound | синтаксическая преобразуемость |
| Validated | локальные и межполевые ограничения |
| Normalized | устранены aliases, defaults, неоднозначность |
| Runtime graph | зависимости доступны, capability готова |

**Must:** после перехода к более сильной форме не повторять те же defensive checks в каждом методе.

**Must not:** смешивать уровни в одном validator (синтаксис + среда + reachability endpoint).

## Сжатие сложности

**Must:** сохранять различия, влияющие на решения потребителя; скрывать несущественные промежуточные фазы.

**Must not:** делать модель слишком бедной (потребитель добавляет внешние флаги) или слишком подробной (раскрывает внутренние фазы без пользы).

## Идентичность, значение, снимок

| Понятие | Критерий | Equality |
|---|---|---|
| Value object | определяется содержимым | structural |
| Entity | устойчивая идентичность во времени | по identifier |
| Runtime handle | активная capability | identity |
| Snapshot | фиксированный момент | structural, без mutation |
| Live view | меняется вслед за runtime | не snapshot |

**Must:** различать в имени и типе: `RuntimeStatusSnapshot` vs `RuntimeStatusView` vs `StatusPublisher`.

**Must not:** один `long version` для format, optimistic-lock, release и generation config — разделять типами (`SchemaVersion`, `ConfigurationRevision`, `LibraryVersion`).

## Область определённости операций

**Must:** явно выбрать способ выражения недопустимого вызова:

- сузить receiver type / value object
- явный result type
- documented exception
- total operation с честной fallback semantics

| Нарушение | Типичный сигнал |
|---|---|
| Аргумент | `IllegalArgumentException` |
| Состояние receiver | `IllegalStateException` |
| Ожидаемая доменная ветвь | domain result |
| Recoverable failure | checked/domain exception (по стилю API) |

**Must not:** формальная totality с молчаливым игнорированием или подменой default — хуже явного отказа.

## Время в модели

| Тип | Смысл | Must not |
|---|---|---|
| `Duration` | длина интервала | путать с deadline |
| `Instant` | точка шкалы | использовать для elapsed без monotonic clock |
| deadline | «не позднее» | заменять `long timeout` без единиц |

**Must:** определить clock source: monotonic для elapsed, wall clock для timestamps.

## Мутация, владение, aliasing

**Must:** для immutable — defensive copy mutable components (`List.copyOf`); оценить глубокую неизменяемость.

**Must:** для buffers, streams, executors, clients — явный ownership contract: borrow / copy / take ownership / share.

**Must not:** `withExecutor(ExecutorService)` без semantics закрытия executor при shutdown.

## Ключевые тезисы

1. Публичный тип публикует множество представимых состояний, не поля.
2. Качество модели = расстояние между representable, valid и reachable.
3. Инвариант — проверяемое утверждение с владельцем и точкой установления.
4. Время, concurrency, ownership — части информационной модели, не детали реализации.
5. Snapshot ≠ live view ≠ handle подписки.
