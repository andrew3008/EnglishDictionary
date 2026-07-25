# Week 11 — Dependency API как публичный контракт

## Контекст

Java-библиотека публикует не только classes. Consumer получает координаты, compile/runtime dependencies, version rules, variants, resources, service providers, processors, Java constraints.

**Ключевой тезис:** публикация — компиляция архитектурного решения в JAR и machine-readable metadata. Успешный build script producer **не доказывает** внешний consumer contract.

## Что получает consumer

Декларация `com.acme:feature-api:2.4.0` требует от build tool:

- идентичность component
- metadata format и variants
- compile и runtime dependencies
- constraints и version requirements
- capabilities и conflicts
- artifacts, classifiers, checksums
- Java runtime compatibility
- sources/Javadoc
- transitive closure

Ошибка на любой стадии — effective API, даже если public methods не изменились.

## Почему dependency — API

Dependency входит в API, если consumer **обязан** знать о ней, иметь type на compile classpath, предоставить runtime или согласовать version.

Контракт через:

- public/protected signatures
- inheritance и implemented interfaces
- annotations (compiler/runtime)
- service/provider protocol
- serialization/wire types
- generated sources
- runtime linkage
- build metadata

**Must:** спрашивать «какую обязанность создаёт для consumer build и runtime?», не «видна ли в JavaDoc».

## Три графа

| Граф | Определение | Риск |
|---|---|---|
| **Declared** | намерение в build script | не показывает versions, substitutions, rejected candidates |
| **Resolved** | результат конкретного resolution context | факт одной сборки, не universal promise |
| **Published** | POM, Gradle Module Metadata, repository layout | должен выводиться из component model |

### Критическая проверка

```text
producer declaration → generated metadata → external resolution → runtime linkage
```

Каждая стрелка может потерять смысл.

**Must-not:** проверять только build — доказывает producer workspace, не publication.

## Четыре точки зрения

| Перспектива | Интерес |
|---|---|
| Producer | компиляция, tests, packaging, удобство разработки |
| Consumer compiler | types для анализа public surface (generics, annotations, supertypes) |
| Consumer runtime | closure для loading, linking, resources, execution paths |
| Build tool / repository | metadata, versions, variants, checksums, availability |

Classification **must** быть корректной для всех четырёх.

## Координаты как identity contract

GAV участвует в: conflict resolution, substitution, cache identity, BOM constraints, vulnerability/SBOM matching, ownership, migration.

| Изменение | Тип |
|---|---|
| package rename без смены coordinates | Java API change |
| coordinates без relocation metadata | dependency API change |

**Must:** стабильные coordinates отражают ownership и capability, не временную implementation detail.

**Must-not:** `core`, `common`, `utils`, `impl` без объяснения consumer role. Переименование ради эстетики дорого.

## Классификация API dependency

### Compile API dependency

Consumer compiler **не способен** корректно использовать supported public surface без types этой dependency.

Функциональный критерий, не «public method упоминает type»:

- public/protected signatures
- generic bounds и arguments
- superclass/superinterfaces
- public fields, record components
- annotations для compiler/processor
- thrown checked types
- public nested types, наследующие external types

### Runtime API dependency

Не нужна consumer compilation, но обязательна для supported runtime: logging provider, driver, native transport, serializer, service provider, agent bridge.

**Must:** публиковать в runtime graph **или** явно возложить выбор provider на consumer.

**Must-not:** «обычно это уже есть» — не контракт.

## Public dependency closure

Минимальный набор modules и version obligations для supported consumer compile/runtime scenario.

| Dependency | Compile | Runtime | Transitive? | Version owner | Absence |
|---|---|---|---|---|---|
| contracts | public types | interfaces | да | library/BOM | compile failure |
| vendor engine | нет | default impl | starter only | BOM | feature unavailable |
| logging API | abstraction | calls | да | application | compile/link failure |
| native transport | нет | optional accel | нет | application | fallback |

**Must:** достаточный, но не максимальный graph. «На всякий случай» расширяет failure space.

## Version mediation

Requested version ≠ selected version. Producer **не контролирует** окончательную версию в application graph без жёсткого ограничения.

**Must:** тестировать с declared version **и** supported version envelope.

### Ranges и dynamic versions

Dynamic request — удобен в development, неоднозначен для release contract.

**Must:** min, tested baseline, upper policy, security override rules, evidence.

**Must-not:** широкий диапазон без matrix tests. Точный pin в reusable library может лишить application выравнивания graph.

## Dependency constraints

Constraint участвует в version selection **только если module уже присутствует**. Не добавляет dependency.

**Must:** `because` с compatibility reason. Strict constraint — только при реальной несовместимости, security floor, coordinated release.

«Мы тестировали только эту версию» ≠ «запретить все остальные».

## BOM и Java platform

BOM описывает tested set совместно релизимых artifacts.

**Must-not:** BOM не добавляет dependency, не исправляет api/implementation edge, не гарантирует behavioral compatibility, не устраняет duplicate classes, не заменяет runtime test, не делает optional integration безопасной.

BOM управляет **selection**, не архитектурой.

### Regular vs enforced platform

| Тип | Поведение | Для reusable library |
|---|---|---|
| Regular platform | согласование requirements; consumer может сильнее | обычно OK |
| enforcedPlatform | принуждает versions downstream | **опасно** |

Enforced platform **must** иметь документированную причину: wire incompatibility, security prohibition, generated-code lockstep, native ABI, coordinated agent distribution.

## Ключевые тезисы

1. Declared, resolved, published graphs — разные объекты.
2. Producer build не доказывает внешний consumer contract.
3. Dependency — API, если создаёт compile, runtime или version obligation.
4. Public dependency closure — минимальный и достаточный.
5. Selected version определяется всем application graph.
6. Constraint направляет selection, не добавляет dependency.
7. BOM — tested set, не semantics.
