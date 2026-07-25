# Week 10 — Измерения совместимости и сценарии

## Контекст

Совместимость — **отношение** между двумя версиями, конкретным потребителем, способом сборки, runtime и сценарием обновления. Фраза «обратно совместимо» неполна без явных координат.

**Ключевой тезис:** зелёный API diff доказывает только один слой обещания.

## Четыре независимых вопроса

| # | Вопрос | Аудитория |
|---|---|---|
| 1 | Запустится ли consumer, скомпилированный против L1, с L2? | JVM / binary |
| 2 | Скомпилируется ли исходный код consumer заново против L2? | Compiler |
| 3 | Сохранится ли наблюдаемое поведение тех же вызовов? | Application |
| 4 | Продолжит ли работать provider, реализующий SPI из L1? | SPI author |

Один ответ **не выводится** из другого.

## Направленность совместимости

| Направление | Типичный сценарий |
|---|---|
| Backward | `old compiled consumer + new library runtime` |
| Forward | `old reader ← new payload` / `new reader ← old payload` |
| Implementor | `old provider + new framework caller` |

**Must:** называть направление явно. «Добавление поля безопасно» — спорно без контекста (Java serialization ≠ JSON с запретом unknown fields ≠ record constructor).

## Compatibility scenario — пять координат

Каждое изменение фиксирует:

| Координата | Примеры |
|---|---|
| **subject** | caller, implementor, subclass author, Spring container, serializer |
| **artifact state** | source, bytecode, persisted data, configuration |
| **old/new direction** | кто обновляется первым |
| **execution boundary** | in-process call, reflection, DI, module access, wire format |
| **success criterion** | links, recompiles, starts, preserves semantics, migrates |

**Must-not:** утверждать «default method совместим» без указания сценария (linking ≠ source conflict ≠ behavioral semantics).

## Compatibility envelope

Библиотека публикует не абстрактную «совместимость», а **envelope** — множество поддерживаемых сочетаний:

- min/max Java runtime
- поддерживаемые Spring Boot / Spring Framework
- диапазон версий provider SPI
- срок поддержки deprecated surface
- правила configuration keys и serialized data
- пути обновления: direct, bridge release, release train

**Must:** ограничивать матрицу реальным обещанием. Без envelope команда либо тестирует случайный набор, либо неявно обещает всё.

## Матрица измерений

| Измерение | Главный вопрос | Доказательство |
|---|---|---|
| Binary | Свяжется ли старый bytecode? | JLS 13, API diff, mixed-version test |
| Source | Скомпилируется ли старый source? | recompilation matrix |
| Behavioral | Сохранится ли смысл вызова? | contract tests, golden behavior |
| Implementor | Корректен ли старый provider/subclass? | compatibility kit |
| Migration | Контролируемый переход возможен? | migration rehearsal |
| Data | Читаются ли old/new payloads? | schema fixtures, bidirectional tests |
| Configuration | Сохранятся keys, defaults, precedence? | context matrix |
| Dependency | Разрешится ли совместимый classpath? | dependency matrix |
| Operational | Сохранятся metrics, logs, health? | telemetry contract tests |

## Compatibility budget

Каждый опубликованный surface расходует будущую свободу:

| Surface | Что закрепляет |
|---|---|
| Public constructor | форму создания |
| Protected method | subclass protocol |
| Property key | имя, тип, default, precedence |
| Bean name | DI seam |
| Metric label | запросы и alerts |

Budget — не запрет на развитие, а **стоимость** будущих изменений. Стабильность там, где ценна; internal/experimental/versioned — где нет.

## Binary compatibility (JLS minimum)

Binary-compatible = ранее существовавшие binaries продолжают связываться без ошибки.

**Не гарантирует:** recompilation, тот же overload, тот же результат, отсутствие runtime exceptions, reflection/serialization, Spring context graph.

**Must:** binary gate для широко используемой библиотеки — необходим, но не итоговая оценка.

### Почему старый bytecode сохраняет target

Bytecode хранит symbolic references (qualifying type + name + descriptor). Старый consumer продолжает ссылаться на старый target, даже если compiler сегодня выбрал бы новый overload.

### Linkage errors — симптом класса изменения

| Ошибка | Типичная причина |
|---|---|
| `NoClassDefFoundError` | удалён/перемещён тип |
| `NoSuchMethodError` | удалён метод или изменён descriptor |
| `NoSuchFieldError` | удалено/изменено поле |
| `IllegalAccessError` | уменьшена accessibility / снят export |
| `IncompatibleClassChangeError` | class/interface/final/sealed relation |
| `AbstractMethodError` | dispatch к реализации без method body |
| `UnsupportedClassVersionError` | class file для более новой Java |

**Must:** smoke test, не вызвавший изменённый method, **не доказывает** binary compatibility. Linkage errors часто проявляются при первом resolution path.

### Mixed-version test

**Must:** consumer fixture, скомпилированный против baseline, запускается **без recompilation** с candidate version.

**Must-not:** перекомпилировать fixture случайно — это проверяет source, а не binary.

## Source compatibility

Recompilation = новый semantic pass: name resolution, overload resolution, generic inference, accessibility, exhaustiveness, annotation processing.

### Типичные source-only breaks (без удаления member)

- новый overload → ambiguity для `null`, lambda, method reference
- новый generic bound → inference невозможен
- default method конфликтует с другим interface
- type с тем же simple name → wildcard import resolution
- новый checked exception в callback
- новый enum/sealed variant → exhaustive switch
- новый abstract method → recompilation provider
- warning → error через `-Werror`

**Must:** проверять на реальных/representative sources, не выводить из JAR diff.

## Behavioral compatibility

Подпись может не измениться. Breaking без structural diff:

- default timeout 1s → 30s
- fail-open → fail-closed
- порядок callbacks
- snapshot → live view
- caching / freshness guarantee
- thread listener
- более строгая validation
- exception type / retry classification
- новый side effect: log, metric, network, resource retention

### Формула review (caller API)

```text
old valid calls ⊆ new valid calls
new guaranteed results ⊇ old guaranteed results
```

**Must:** для SPI читать с позиции provider — расширение input domain framework может быть breaking, даже если caller API считает расширение улучшением.

## Implementor compatibility

Отдельная аудитория: provider компилируется независимо, может быть старше framework.

Включает: загрузку binary, recompilation, callback protocol, input domain, lifecycle/threading/ordering, отсутствие новых обязательных capabilities.

### Default method — только часть задачи

**Must:** semantic default консервативен — воспроизводит прежнее поведение, не заявляет новую capability.

**Must-not:** `UnsupportedOperationException` (break при runtime) или оптимистичное `true` (framework использует capability, которой provider не имеет).

## Migration compatibility

Между «совместимо» и «невозможно». Оценивает: bridge release, поочерёдное обновление, adapter, раннее обнаружение, обратимость, rollback, механическая замена.

**Migration-incompatible:** добавление permitted subtype в sealed hierarchy — binary-compatible, но exhaustive switch получает новый variant и падает при выполнении.

## SemVer prerequisite

SemVer классифицирует incompatible public API → major. Применим **только после** явного определения public API (Java + SPI + properties + beans + metadata + data + operations).

**Must-not:** major не заменяет migration guide, deprecation period, support coordination.

**Patch:** bug fix безопасен, если прежнее поведение явно нарушало contract; если consumers разумно зависели от ambiguity — нужен rollout plan.

## Ключевые тезисы

1. Совместимость — отношение между версиями, участниками и upgrade scenario.
2. Binary, source, behavioral, implementor — независимы.
3. JLS 13 — minimum linking guarantee, не полный contract.
4. Старый bytecode хранит symbolic target; recompilation заново выполняет resolution.
5. Additive overload может ломать source и менять behavior.
6. Input expansion для caller может быть breaking для SPI provider.
7. SemVer требует inventory public contract.
