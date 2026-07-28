# Week 14 — Defaults and binding model

## Контекст

Слово «default» скрывает разные механизмы. Binding model фиксирует snapshot bootstrap-конфигурации и различает absent / null / empty / Optional.

## Таксономия defaults

| Вид | Где | Когда | Наблюдаемость |
|---|---|---|---|
| Model default | initializer/constructor | нет binding | properties bean |
| `@DefaultValue` | constructor param | constructor binding | binding schema |
| Condition default | matchIfMissing | activation | bean graph |
| Runtime default | SDK fallback | после передачи config | часто невидим |
| Metadata default | metadata JSON | IDE/docs | не управляет runtime |
| Deployment default | chart/template | до старта | explicit property |

**Must:** один authoritative behavioral default + consistency tests при нескольких механизмах.

**Must-not:** считать metadata = runtime; SDK default стабильным без assessment; condition default согласованным с properties object без проверки.

## Java default ≠ Environment value

Поле с `Duration.ofSeconds(5)` видно в typed model при отсутствии ключа; `Environment.getProperty(...)` может вернуть `null`.

Критично для conditions и placeholders: model default `enabled=true` + `@ConditionalOnProperty` с missing=false → расхождение bean graph.

Правило: семантическая одинаковость default во всех decision points; **не** предполагать видимость model default в Environment. Минимизировать повторное чтение raw properties после binding.

## Defaults активации

| Policy | Missing означает |
|---|---|
| opt-out | активна; `enabled=false` отключает |
| opt-in | неактивна; `enabled=true` включает |
| context-derived | вычислить из classpath/beans; explicit переопределяет |

Таблица состояний: отсутствует / true / false / некорректно → model + condition + runtime. Разные интерпретации string/boolean/absence → недетерминизм.

## @ConfigurationProperties vs @Value

| | `@ConfigurationProperties` | `@Value` |
|---|---|---|
| Группировка | естественная | ручная |
| Relaxed binding | полная | ограниченная |
| Metadata | да | нет |
| Validation | объектная | вручную |
| SpEL | нет | да |

Для platform starter — принимать данные, не выражения. `@Value` — internal wiring; несколько связанных свойств / invariant → properties type.

## Immutable model

Record / final class с constructor binding — snapshot bootstrap. Исключает частичный объект, runtime mutation, порядок setters.

Immutable ≠ всегда корректный: invariants при construction/validation. Изменение файла после startup не создаёт новый объект.

Properties type = boundary DTO, не domain service и не mutable SDK builder.

## Records и constructor binding

Один parameterized constructor → constructor binding; несколько — `@ConstructorBinding`. Nested records — nested groups.

Регистрация: `@EnableConfigurationProperties` или scanning. **Must-not:** одновременно `@Component` / `@Bean` / `@Import`.

Compile с `-parameters` — часть build contract. Переименование record component = изменение configuration API.

## @DefaultValue и nested absence

`@DefaultValue` — строка для конвертации при отсутствии. Empty `@DefaultValue` на nested может создать nested object при отсутствии всей группы → всегда validation; стирает «группа не задана» vs «default object».

Оправдано, когда группа концептуально всегда существует. Если presence = capability — сохранить explicit absence.

## null / Optional / empty / absent

| Состояние | Смысл | Рекомендация |
|---|---|---|
| Absent key | default / auto-detect | моделировать явно |
| null в target | не создано | не обычное user state |
| Empty string/collection | явно пусто | определить допустимость |
| Optional | попытка выразить absence | обычно **не** в properties |

Spring Boot не рекомендует `Optional` в `@ConfigurationProperties`. Предпочтительнее: boolean/mode; nullable только на boundary + validation; enum `AUTO|ENABLED|DISABLED`; пустая collection только при валидной семантике «нет элементов».

Контракт объясняет: empty string → null?; можно ли очистить default collection override'ом.

## JavaBean mutability

Setter binding — legacy / constraints. Mutable bean = bootstrap phase, не runtime control plane.

**Must-not** в setter: открывать соединение, читать файл, getBean, вычислять policy по другим properties.

После binding → внутренний immutable runtime specification. Публичен configuration language, не мутация properties bean.

## Nested types

Хорошая группа: общий invariant / switch / reusable concept; absence имеет смысл. Глубина >4–5 → проверить, не implementation tree ли это.

Перенос свойства между группами меняет key → migration.

## Collections и maps

Коллекция задаёт identity, ordering, duplicates, replacement.

**Lists:** Spring Boot — **replacement**, не element merge. Override списка целиком заменяет baseline.

**Maps:** динамический key space; bracket notation для special keys; merge vs удаление inherited entries — явная семантика.

**Must:** документировать, что происходит при profile override списка/map; как удалить унаследованный map entry.

## Ключевые тезисы

- Defaults разных слоёв согласованы, но не взаимозаменяемы.
- Model default не публикуется автоматически в Environment.
- Immutable properties = bootstrap snapshot DTO.
- Absent ≠ null ≠ empty ≠ Optional.
- Lists replace; map merge/delete — явный контракт.
