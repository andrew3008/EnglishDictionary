# Week 11 — Классификация, scopes, ABI leakage

## Контекст

Gradle `java-library` и Maven scopes выражают роли dependencies. Неверная классификация ломает внешних consumers при успешной локальной сборке.

## api и implementation (Gradle)

```java
dependencies {
    api 'com.acme:public-contracts:3.2.0'
    implementation 'com.vendor:internal-engine:7.4.1'
}
```

| Configuration | Consumer compile | Consumer runtime |
|---|---|---|
| `api` | да, transitively | да |
| `implementation` | нет | да |

**Must-not:** начинать с build configuration → оправдывать код.

**Правильный процесс:**

```text
supported public surface
  → required consumer types
  → classify dependency role
  → verify published metadata
```

`implementation` **не способ** спрятать протёкший public type. Сначала убрать leakage архитектурно или признать dependency частью API.

## compileOnly и compileOnlyApi

### compileOnly

Producer compiler нужен; runtime graph автоматически — нет.

Типично: container-provided API, optional integration с conditions, relocated/shaded dependency, static analysis annotations.

**Must:** явно определить runtime owner: application, platform, container, shaded artifact.

**Must-not:** локальная компиляция успешна, runtime ownership не определён.

### compileOnlyApi

Types нужны consumer compilation; runtime предоставляет отдельно. Близко к JPMS `requires static transitive`.

**Must:** узкая форма — только если external runtime provider стабильное assumption (API контейнера).

### compileOnly ≠ optional

Optional type в top-level signature, static initializer, eagerly loaded class → runtime падает до проверки availability.

Optionality **must:** metadata + bytecode reachability + discovery + conditions + tests — согласованы.

## runtimeOnly

Consumer source компилируется против abstraction; runtime нуждается в implementation.

```java
api 'org.slf4j:slf4j-api:2.0.17'
runtimeOnly 'ch.qos.logback:logback-classic:1.5.18'
```

**Must-not:** reusable library навязывает application-wide provider без необходимости.

Различать:

| Роль | Кто выбирает provider |
|---|---|
| Library с любым provider | application/starter |
| Opinionated starter/distribution | starter |
| Application | владеет окончательным graph |

## Annotation processors

**Must:** `annotationProcessor`, не `implementation` или `runtimeOnly`.

Processor — build-time tool с отдельным classpath. Runtime publication увеличивает attack surface.

### Processor как отдельный продукт

Build API включает: supported compiler/JDK, annotations, generated packages/names, deterministic output, incremental semantics, diagnostics, generated code compatibility, explicit consumer declaration.

**Must:** onboarding явный — обычный POM не выражает «подключи как processor» переносимо.

## Maven scopes

| Scope | Producer compile | Consumer transitive | Смысл |
|---|---|---|---|
| compile | да | да | API/обычная |
| runtime | нет | runtime | runtime implementation |
| provided | да | нет | container/JDK |
| test | нет | нет | только tests |
| import | — | — | BOM only |

Gradle mapping (lossy):

```text
Gradle api            → Maven compile
Gradle implementation → Maven runtime
```

**Must-not:** «POM сгенерирован без warning» ≠ одинаковая semantics для Maven и Gradle.

## ABI leakage

### Прямая утечка

```java
public interface ReportClient extends VendorClient {
    VendorResult execute(VendorRequest request);
}
```

Vendor types — consumer compile contract. `implementation` для vendor artifact → внешний compiler не читает API.

### Generic и inheritance leakage

```java
public abstract class Repository<T extends VendorEntity>
        implements VendorLifecycle {
    protected abstract List<VendorKey> keys();
}
```

Erasure не спасает: bounds, supertypes, protected extension surface — dependency ABI для subclassing.

### Annotation leakage

```java
@VendorGeneratedModel
public record CustomerId(String value) {}
```

| Retention | Obligation |
|---|---|
| SOURCE | annotation processor |
| CLASS | bytecode tooling |
| RUNTIME | reflection framework |

**Must-not:** убрать annotation artifact из POM без доказательства, что consumer не обязан разрешать.

## Dependency surface шире signatures

Inventory **must** учитывать:

- exception hierarchy
- annotations и processors
- `META-INF/services`
- `module-info.class`
- Spring/JSON/native metadata
- configuration schemas
- native libraries
- package names для reflection
- license files при redistribution

### Effective dependency API

Consumers регулярно import transitive library напрямую → фактически effective API, даже при `implementation`.

**Must-not:** молчаливое удаление. Нужны evidence, deprecation path, migration note.

## Optional dependencies

Maven `optional=true`: нужна module, но downstream не получает transitively. Consumer optional feature **must** declare directly.

**Must-not:** optional означает безопасное отсутствие type при class loading, auto feature detection, совместимость со всеми версиями.

### Optionality как capability boundary

Минимальное доказательство:

- [ ] base artifact загружается без dependency
- [ ] public base API не требует её types
- [ ] discovery не активирует integration
- [ ] failure сообщает, как подключить feature
- [ ] negative-classpath test проходит

## Transitive dependencies

Transitivity сокращает onboarding, увеличивает coupling. Каждая transitive может: расширить classpath, изменить mediation, добавить service provider, принести vulnerable component, создать duplicate classes, изменить startup, стать accidental API.

**Must:** consumer явно объявляет modules, чьи types использует напрямую.

Документация **must** различать:

- одну dependency для типового использования
- дополнительные direct dependencies для optional integrations
- запрещённые прямые imports implementation libraries

## Lossy translation POM ↔ GMM

Gradle Module Metadata выражает: variants, attributes, capabilities, rich constraints, feature graphs, multiple artifacts.

Maven POM не имеет прямого эквивалента. Maven consumer выберет иначе или не увидит conflict.

**Must:** явно решить degraded consumer mode:

- Maven поддерживается полноценно
- Maven — ограниченный feature set
- Maven — отдельные coordinates
- Gradle-only product

**Must-not:** молчаливый degraded mode — build проходит, graph неправильный.

## Variants, capabilities, feature variants

### Variant

Оправдан, если: consumer выбирает детерминированно, artifacts имеют разные closures, fallback понятен, Maven consumption спроектирован, matrix тестируется.

**Must-not:** variant explosion — множество почти одинаковых variants.

### Capability

Mutually exclusive providers:

```text
acme-json-jackson  provides com.acme:json-binding
acme-json-gson     provides com.acme:json-binding
```

**Must-not:** capability как единственный enforcement для cross-build-tool product. Дополнительно: explicit coordinates, runtime exclusivity check, Maven Enforcer, отсутствие transitive provider selection.

### Feature variant vs отдельный artifact

Отдельные coordinates лучше, если: feature подключается вручную, Maven consumers важны, dependency тяжёлая, cadence отличается, отсутствие feature очевидно по graph.

## Ключевые тезисы

1. `api` — consumer compile contract; `implementation` — internal с runtime presence.
2. `compileOnly` требует явного runtime owner.
3. `compileOnlyApi` — дорогая форма, только для container contract.
4. Annotation processor — build API, не runtime library.
5. ABI leakage через supertypes, generics, annotations, protected surface.
6. Optional — только при согласованности metadata, bytecode, conditions, tests.
7. Gradle concepts теряются в POM — нужна Maven portability policy.
