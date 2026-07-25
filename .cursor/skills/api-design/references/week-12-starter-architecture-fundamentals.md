# Week 12 — Архитектура стартера: fundamentals

## Контекст

Spring Boot starter — не JAR с dependencies и beans. Это adoption architecture: dependency graph, activation rules, configuration namespace, beans, replacement seams, failure behavior, diagnostics, evolution path.

**Ключевой тезис:** starter — опубликованная policy boundary, переводящая classpath, properties и user beans в предсказуемый runtime graph.

## Что получает потребитель

После одной dependency:

| Решение | Примеры |
|---|---|
| compile/runtime classpath | libraries, providers |
| auto-configuration candidates | conditions, ordering |
| beans и names | types, qualifiers |
| defaults без явной конфигурации | properties, behavior |
| user bean suppression | backoff |
| startup failure | analyzers, diagnostics |
| metrics, health, logs | operational surface |
| AOT/native support | hints, reachability |
| disable/replace | properties, custom beans |

**Must:** минимизировать начальную работу, **не скрывать** важные решения.

## Три системы

| Система | Содержание | Blast radius |
|---|---|---|
| **Runtime** | vendor SDK, routing, policy, resources | domain behavior |
| **Bootstrap** | auto-config, conditions, properties, backoff, hints, analyzers | platform-wide |
| **Adoption** | starter dep, BOM, metadata, samples, migration, compatibility matrix | onboarding |

**Must-not:** проектировать только runtime — adoption и bootstrap формируются случайно.

## Runtime plane и bootstrap plane

```text
application
    → starter dependency policy
        → autoconfigure
            → runtime/core
                → public API/SPI
```

**Must-not (запрещённое направление):**

```text
runtime/core → autoconfigure → application context
```

Core вызывает `ApplicationContext`, читает `Environment`, ищет beans → service locator. Runtime semantics зависят от bootstrap order.

### Допустимые Spring-зависимости в runtime

Стабильные abstractions Spring Framework (`Resource`, `TaskExecutor`, transaction interfaces) — OK, если часть контракта.

**Must-not:** Spring Boot auto-configuration API, condition evaluation, application startup lifecycle в runtime plane.

## Физическая архитектура

| Вариант | Форма | Когда | Риск |
|---|---|---|---|
| Combined | один `acme-spring-boot-starter` | малая стабильная функция | bootstrap + runtime contracts срастаются |
| Two-module | autoconfigure + starter | типичная auto-config | формальное разделение без границы |
| Layered | api, core, autoconfigure, starter, slices | платформенная технология | избыточная модульность |

Количество модулей — **design decision**, не шаблон.

### Критерий отдельного артефакта

Отдельный artifact **must**, если хотя бы одно:

- осмысленное использование без остальных частей
- другой dependency closure
- отдельный compatibility surface
- отсутствие проверяемо через classpath
- иной lifecycle/cadence
- обязательное vs optional integration
- нужен тестам, не production
- самостоятельный public API/SPI

**Must-not:** package boundary, ошибочно превращённая в publication boundary.

## Рекомендуемая топология (platform)

```text
acme-api                         stable caller API and SPI
acme-core                        runtime implementation
acme-spring-boot-autoconfigure   bootstrap, properties, Spring seams
acme-spring-boot-starter         dependency-only adoption artifact
acme-spring-boot-starter-test    optional test fixtures
acme-integration-<technology>    optional slices
acme-bom                         tested release set
```

Не требование семи модулей. api+core merge OK при малом surface. BOM — при нескольких выбираемых artifacts.

## Responsibility matrix

| Модуль | Содержит | Must-not содержать |
|---|---|---|
| API | caller types, SPI, exceptions, annotations | Spring conditions, vendor impl |
| Core | algorithms, adapters, state, lifecycle | auto-config discovery, Environment lookup |
| Autoconfigure | conditions, properties, bean assembly, hints | тяжёлую business logic |
| Starter | dependency declarations, metadata | component scanning, runtime algorithms |
| Integration slice | adapter + bootstrap optional tech | unrelated integrations |
| Test starter | fixtures, fakes, test auto-config | production defaults |

### Responsibility test

«Если заменим Spring Boot другим composition mechanism — останется ли смысл class?»

- да → API/core
- только для bean graph → autoconfigure
- только dependencies → starter

## Dependency graph invariants

**Must:**

- API не зависит от autoconfigure
- Core не ищет beans/properties через глобальный context
- Autoconfigure зависит от API/core, не наоборот
- Optional integration не обязательна transitively для base starter
- Starter не экспортирует случайный vendor API
- Test fixtures не в production runtime
- Processors не published как runtime
- BOM управляет versions, не маскирует ошибочные dependencies

**Must:** превращать в build gates: graph assertions, ArchUnit/JPMS, api/implementation checks, POM/GMM snapshots, base starter без optional libs, negative compile fixture.

## Public API placement

### Два Java surface

| Surface | Размещение | Примеры |
|---|---|---|
| Runtime API | api/core artifact | types для application code |
| Spring composition API | autoconfigure (явная policy) | customizers, providers, marker beans |

**Must-not:** экспортировать configuration classes как удобный API для `@Import`.

Auto-configuration class может быть public для exclusions/ordering/diagnostics, но nested config и `@Bean` methods — internal.

## Один starter или flavors

| Один starter | Несколько flavors |
|---|---|
| один production mode | взаимоисключающие режимы |
| optional через user bean | разные тяжёлые dependencies |
| transports сосуществуют | servlet vs reactive |
| единый adoption path | `starter-servlet` vs `starter-reactive` |

Flavors **must-not** дублировать policy. Shared modules для runtime и auto-config rules; flavor — только dependency choice.

## Package architecture

```text
com.acme.platform.feature.api
com.acme.platform.feature.spi
com.acme.platform.feature.core
com.acme.platform.feature.autoconfigure
com.acme.platform.feature.autoconfigure.condition
com.acme.platform.feature.autoconfigure.properties
```

**Must-not:** `config`, `service`, `util`, `impl` без contract boundary.

Auto-configuration packages **must-not** быть target component scanning. Явный `@Import` → детерминированный graph.

## Integration slices

Slice — вертикальный фрагмент при конкретной technology: Servlet, WebFlux, Kafka, Actuator, vendor SDK, native, test impl.

**Must:** slice удаляем из classpath → base feature стартует в явном degraded/reduced mode.

**Must-not:** удаление integration JAR → `NoClassDefFoundError` в base auto-configuration.

## Ключевые тезисы

1. Starter проектирует adoption, не только bean creation.
2. Runtime, bootstrap, adoption — разные системы.
3. Один dependency UX не требует одного implementation module.
4. Core выполняет функцию; autoconfigure собирает; starter публикует dependency policy.
5. Starter artifact может быть пустым JAR с широким API.
6. Integration slices удаляемы без поломки base feature.
7. Физический модуль оправдан dependency, compatibility или release boundary.
