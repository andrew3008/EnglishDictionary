# Week 10 — Spring, data, configuration, operations

## Контекст

Starter и platform library публикуют контракты за пределами Java signatures: wire formats, properties, beans, auto-configuration, metrics, health.

**Must:** API diff tool **не видит** documented behavior, property defaults, Spring conditions, bean names, serialized JSON, operational surfaces.

## Data и wire compatibility

POJO shape становится schema через Jackson, JSON-B, protobuf adapter, DB mapper, configuration binder:

| Элемент wire contract |
|---|
| property names |
| constructor/creator parameters |
| nullability |
| unknown-field policy |
| enum representation |
| polymorphic type ids |
| date/time format |
| numeric ranges |
| default omission |

Изменение field visibility — нейтрально для одного mapper config, breaking для другого.

**Must:** serializer profile — часть test fixture.

### Reader/writer matrix

| Writer | Reader | Проверка |
|---|---|---|
| old | old | baseline fixture корректен |
| old | new | backward read |
| new | old | forward read |
| new | new | candidate semantics |

Forward path: writer не включает новые fields для старого peer **или** old reader игнорирует unknown — **protocol decision**, не свойство JSON.

### Tolerant reader — границы

**Must-not:** «unknown values всегда игнорируем», если новый field меняет authorization, currency, units — старый reader не может безопасно «не заметить».

Различать: optional enrichment vs meaning-changing data.

## Configuration compatibility

Property contract шире ключа:

| Элемент |
|---|
| canonical key и relaxed-binding aliases |
| type и conversion |
| units |
| default |
| validation constraints |
| precedence между sources |
| activation conditions |
| startup-only vs dynamic semantics |
| secret/sensitivity |
| metadata и IDE hints |

**Must-not:** сохранить key, но изменить milliseconds → seconds — breaking без Java diff.

### Rename через alias period

```text
v1: acme.client.timeout
v2: acme.client.request-timeout  (preferred)
    acme.client.timeout          (deprecated alias)
v3/major: old alias removed
```

**Must:** conflict policy, если заданы оба (обычно preferred wins + diagnostic).

**Must-not:** silent last-property-source wins — миграция зависит от окружения.

### Default — самый массовый behavior

Изменение default затрагивает всех, кто ничего не настроил. Часто **серьёзнее**, чем удаление редко используемого method.

**Must:** default changes — отдельная risk assessment и rollout plan.

## Spring bean compatibility

### Bean type

Тип bean — injection, conditions, collections, lookup, replacement. Замена concrete → interface может перестроить context graph через condition evaluation.

### Bean name и qualifier

Name = контракт, если: документирован, `@Qualifier`, condition ссылается, tests/ops lookup, dashboards показывают как stable identifier.

**Must-not:** считать переименование generated method безопасным после массового effective usage.

### Scope, laziness, lifecycle

| Изменение | Эффект |
|---|---|
| singleton → prototype | identity, ownership, performance |
| eager → lazy | failure timing |
| destruction callback | shutdown |
| proxying | class identity, equality |

### Ordering

Изменение `@Order`, `Ordered`, `@Primary`, tie-break → другой provider без signature diff.

**Must:** order contract с direction, reserved ranges, deterministic rule для равенства.

## Auto-configuration evolution

### Conditions — executable protocol

Classpath, bean, property, resource conditions определяют startup graph. Изменение `matchIfMissing`, search strategy, required class, placement → другой graph.

### Backoff

`@ConditionalOnMissingBean` публикует replacement seam. Стабильность:

- meaningful replacement type
- search scope
- handling multiple candidates
- default configuration после backoff
- validation только для активной implementation
- diagnostics при incomplete user replacement

**Must-not:** broad backoff на low-level helper — internal bean становится SPI.

### Auto-configuration class name

Имя auto-configuration class — contract для `spring.autoconfigure.exclude` и annotation exclusions. Nested configuration и bean methods — internal.

### Registration metadata

`AutoConfiguration.imports`, condition metadata, runtime hints, failure analyzers — machine-facing contracts. Relocation в metadata ломает discovery при сохранённых classes.

## Operational compatibility

### Metrics

Metric name, type, unit, label keys, label value domain, aggregation semantics — API для dashboards/alerts.

Rename или high-cardinality label может ломать operations сильнее Java caller.

### Health и Actuator

Health component name, status mapping, details shape, endpoint id, access policy — probes и automation.

Изменение `UP`/`OUT_OF_SERVICE` semantics или group membership — operational breaking change.

### Logs и diagnostics

Log text редко дословный contract. Stable event/error code, structured fields, severity — contract.

**Must:** machine consumers зависят от ids; prose свободен.

### Failure timing

Validation startup → first request: итоговая ошибка та же, deploy safety хуже. Обратный перенос останавливает apps с неиспользуемой misconfiguration.

**Must:** timing — часть compatibility review.

## Dependency compatibility

Consumer получает resolved graph, не только ваши classes. Обновление dependency может вызвать:

- исчезновение transitively relied-on type
- version conflict
- другой service provider
- logging binding conflict
- classpath shadowing
- `NoSuchMethodError` между third-party components
- другую security policy
- повышение minimum Java

### api и implementation

External type в public/protected signature → API dependency. Но reflection, annotations, exceptions, serialization тоже создают effective dependency.

**Must-not:** transitive dependency как convenience API — документация/samples с import third-party type без direct dependency = обещание presence и version.

### Shading/relocation

**Must-not:** relocation types, протёкших в API, service metadata, serialized names, reflection config. Shaded copy — отдельные security/update обязанности.

## BOM и release train

BOM/constraints выравнивают versions, уменьшают combinatorial space.

**Must-not:** BOM доказывает совместимость произвольной override version.

Различать: managed version, tested range, strictly supported set, user override policy, failure diagnostic.

В release train каждый artifact может быть individually binary-compatible, но комбинация — нет. **Must:** проверять resolved graph целиком и representative application contexts.

**Must-not:** starter добавляет property «version» для build dependency, если version управляется BOM — runtime config и build-time selection — разные contracts.

## Additive evolution

Additive ≠ compatible. Классифицировать каждый новый элемент:

- влияет на resolution?
- расширяет input/output domain?
- создаёт exhaustive variant?
- активируется default для существующих users?
- меняет Spring selection?
- увеличивает dependency/data/operations surface?

### Sibling capability interface

```java
public interface PolicyProvider {
    Collection<Policy> load();
}
public interface RefreshablePolicyProvider extends PolicyProvider {
    void refresh();
}
```

Framework проверяет capability осознанно. Старый SPI не получает фиктивный default.

### Options object

Immutable options + builder для растущих параметров без overload explosion. Defaults options object — behavioral contract.

## Expand–migrate–contract

| Фаза | Действие |
|---|---|
| Expand | добавить новый surface, сохранив старый |
| Migrate | перевести consumers/providers/data, собрать evidence |
| Contract | удалить старый surface в разрешённой version boundary |

Работает для methods, property keys, bean seams, data fields, module names.

### Dual write/read

```text
read: new -> fallback old
write: old + new during transition
cutover: new only after old-reader population = 0
```

**Must:** owner, exit criteria, target release удаления для каждой transition branch.

**Must-not:** permanent dual protocol — удваивает test matrix и создаёт неоднозначность precedence.

## Adapters, facades, shims

**Adapter:** локализует разрыв old API/SPI → new model. **Must-not:** silently invent data или скрывать security differences.

**Facade:** отделяет consumer API от volatile vendor SDK.

**Shim must have:** deprecation warning/metric, owner, usage telemetry без PII/high-cardinality, known semantic gaps, removal condition, tests old→new и new→old.

## Capability negotiation

`int version()` без protocol бесполезен. Нужны: supported range, capability set, selection rule, downgrade behavior, diagnostic.

**Must-not:** version handshake для монолитно обновляемого internal implementation.

Capabilities предпочтительнее версии для ортогональных features. Framework **must-not** вызывать optional operation без успешной negotiation.

## Ключевые тезисы

1. Property key без type, units, default, precedence — неполное описание API.
2. Spring conditions, backoff, bean selection ломаются без Java diff.
3. Metrics, health, diagnostics — API для SRE.
4. Dependency graph и published metadata — compatibility envelope.
5. BOM — managed/tested set, не semantics.
6. Additive evolution требует той же дисциплины, что removal.
7. Expand–migrate–contract превращает разрыв в управляемую последовательность.
