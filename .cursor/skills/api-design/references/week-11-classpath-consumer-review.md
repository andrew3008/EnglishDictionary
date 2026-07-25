# Week 11 — Classpath conflicts, consumer matrix, review

## Контекст

Dependency compatibility включает runtime linkage, duplicate classes, module path, classloader boundaries — не только declared edges.

## Classpath failure modes

| Failure | Причина |
|---|---|
| `ClassNotFoundException` | class запрашивается динамически, artifact отсутствует |
| `NoClassDefFoundError` | type был при compile, отсутствует/не инициализируется runtime |
| `NoSuchMethodError` | binary-incompatible версия dependency |
| `AbstractMethodError` | interface/provider versions не согласованы |
| `IncompatibleClassChangeError` | hierarchy несовместима |
| `ServiceConfigurationError` | provider metadata/type/constructor некорректны |
| LayerInstantiationException | split package, module graph violations |

### Late linkage

Classpath стартует и падает при первом вызове path.

**Must-not:** simple context startup или `Class.forName` одного entry point — не доказывает closure.

Scenario tests **must** активировать: public method paths, SPI loading, serialization, optional branches, reflection/proxies, shutdown, native binding.

## Duplicate classes

Два JAR с одним binary name → classloader загружает первый найденный. Порядок зависит от launcher, packaging, container.

**Must-not:** duplicate class — не version mediation. Build tools разрешают modules; JVM видит binary names.

### Detection gate

- duplicate `.class` entries
- embedded copies dependencies
- conflicting `META-INF/services`
- duplicate module names
- одинаковые packages в named modules
- platform-specific native libraries с одинаковыми именами

Exclusion **must** следовать доказательству, что оставшаяся версия обеспечивает всем paths требуемый API.

## Split packages и module path

Split package — один package в нескольких modules/JAR.

| Context | Поведение |
|---|---|
| Classpath | возможно, ownership неясна |
| JPMS module layer | два named modules не экспортируют один package без конфликтов |

Причины: monolith split без migration, generated classes в другом artifact, test fixtures в production, shading без relocation, partial fork.

**Must:** физическая модульность совпадает с package ownership.

## Classloader isolation

Один process — не один type universe. Одинаковый binary name в разных classloaders — разные runtime types.

Следствия: `ClassCastException`, static singleton не process-wide, `ServiceLoader` зависит от loader, TCCL — часть discovery, cache удерживает deployment classloader.

### Boundary types

Через classloader boundary **must** передавать: JDK types, shared API types (parent loader), immutable wire representations, JMX Open Types, strings/bytes с versioned schema.

**Must-not:** implementation object между изолированными planes — скрытая identity dependency.

## Consumer matrix

| Consumer | Проверяет |
|---|---|
| Gradle Groovy DSL | GMM, variants, capabilities |
| Gradle без GMM | POM fallback, degraded semantics |
| Maven | scopes, optional, dependencyManagement |
| JPMS module-path | module name, exports, split packages, services |
| Plain classpath | runtime closure, duplicate classes |
| Previous-version source | source migration |
| Previous-version binary | binary linkage |

### Corporate baseline envelope

**Must** назвать: min/current Gradle, min/current Maven, Java baseline, repository manager, metadata formats, Spring Boot line (если integration), offline/mirrored policy.

**Must-not:** случайно работающая комбинация как неявное обещание.

## External consumer fixtures

### Gradle consumer

**Must:** зависимость по GAV из staging repository, **не** `project()`.

Проверять: compile classpath, runtime classpath, selected variants, absence accidental dependencies.

### Maven consumer

Доказывает POM contract: compile supported usage, runtime scenario, dependency tree для baseline comparison.

Особенно features через Gradle-specific metadata — Maven может получить другой graph.

### Negative fixtures

**Must** не компилироваться или не разрешаться:

- import implementation type
- optional feature без direct dependency
- два mutually exclusive providers
- неподдерживаемая Java version
- split package на module path
- processor ошибочно отсутствует

## Dependency compatibility gates

Release comparison **must** охватывать:

- public/protected class API
- POM dependencies/scopes/optional/exclusions
- GMM variants/attributes/capabilities/constraints
- artifact filenames/classifiers
- module descriptor
- service providers
- embedded components
- licenses/notices
- Java bytecode baseline
- reproducible digests

### Graph scenarios (minimum)

```text
old consumer binary + new library graph
old consumer source + new publication
new library + lowest supported dependency versions
new library + platform-selected current versions
base artifact - optional dependency
Maven consumer + POM only
Gradle consumer + GMM
module-path consumer
```

Один dependency report **не заменяет** matrix.

## Dependency API Review

### Шаг 1. Consumer contract

- кто подключает artifact
- какие types используются напрямую
- default runtime scenario
- кто выбирает implementations
- кто владеет versions
- какие build tools поддерживаются

### Шаг 2. Четыре inventories

| Inventory | Содержание |
|---|---|
| Public Type Dependency | types в signatures |
| Runtime Closure | runtime obligations |
| Published Metadata | POM/GMM |
| Embedded Component | shaded/embedded |

Каждая dependency — хотя бы в одном inventory с owner/reason.

### Шаг 3. Сопоставить graphs

Declared vs resolved vs published. Для каждого различия: намеренно? какой consumer видит? риск? test?

### Шаг 4. Portability

POM/GMM mapping, optional features, Maven consumption, JPMS, classloader boundaries. Gradle-only semantics — явно отмечены.

### Шаг 5. Release gates

| Решение | Gate |
|---|---|
| vendor SDK не API | negative compile + ABI scan |
| provider optional | missing-classpath runtime test |
| два providers exclusive | capability/runtime conflict test |
| Maven поддерживается | external Maven consumer |
| JAR reproducible | isolated byte comparison |
| release immutable | promotion/digest check |

## Published Contract Manifest

Связывает architecture и publication output:

| Surface | Declared intent | Published | Verification |
|---|---|---|---|
| Java API dependency | contracts exported | POM compile, GMM apiElements | compile fixture |
| runtime provider | engine internal | POM runtime, GMM runtimeElements | black-box run |
| optional Kafka | separate integration | separate GAV | absence/presence tests |
| platform | recommended set | BOM + constraints | Gradle/Maven matrix |
| services | two providers | service metadata | discovery test |

Manifest полезен при миграции Gradle DSL, repository manager, publishing plugin.

## Анти-паттерны

| # | Анти-паттерн |
|---|---|
| 1 | `api` для всего — compile classpath разрастается |
| 2 | `implementation` для протёкшего type — внешний consumer не компилирует |
| 3 | optional только в POM — base eagerly загружает type |
| 4 | BOM force всего graph — конфликт с platform owner |
| 5 | Gradle-only semantics без Maven policy |
| 6 | fat JAR как reusable library |
| 7 | blind shading без services/reflection/licenses/SBOM |
| 8 | `duplicatesStrategy = EXCLUDE` глобально |
| 9 | publication через `project()` — не читает POM/GMM |
| 10 | rebuild при promotion |
| 11 | перезапись release version |
| 12 | producer lock как universal truth |

## Контрольный чек-лист

### Public dependency surface

- [ ] external types в public/protected signatures инвентаризированы?
- [ ] generic bounds, annotations, supertypes, record components проверены?
- [ ] checked exceptions и processor obligations учтены?
- [ ] accidental transitive imports обнаружены?
- [ ] public API не раскрывает shaded/internal packages?

### Configuration roles

- [ ] `api` только для consumer compile obligations?
- [ ] `implementation` не скрывает ABI leakage?
- [ ] `compileOnly` имеет runtime owner?
- [ ] annotation processors отделены от runtime?

### Metadata portability

- [ ] POM и GMM проверены отдельно?
- [ ] lossy mapping задокументирован?
- [ ] Maven consumer получает поддерживаемый graph?
- [ ] classifiers не заменяют modules?

### Release publication

- [ ] publication из software component?
- [ ] build reproducible, byte comparison?
- [ ] staging consumer tests — только repository GAV?
- [ ] promotion сохраняет исходные bytes?
- [ ] release version неизменяема?

## Ключевые тезисы

1. Duplicate classes — JVM identity conflict, не version conflict.
2. Split package — несогласованность package и artifact ownership.
3. Classloader boundary требует shared или wire types.
4. External Gradle/Maven fixtures — обязательное доказательство publication.
5. Dependency API Review начинается с consumer obligations, не Gradle configs.
6. Published Contract Manifest связывает намерение, metadata, evidence.
