# Неделя 05 — Spring creation pipeline и decision matrix

## Container-owned construction

**Must:** application developer получает injected capability, не собирает internal graph.

```java
final class OrderService {
    OrderService(SpanFactory spanFactory) { ... }
}
```

**Must not:** starter требует ручной сбор internal runtime от каждой команды.

## Constructor injection

**Must:** обязательные dependencies явны; bean valid at creation.

Optional: `Optional<T>`, `ObjectProvider<T>`, collection injection, nullable — **разные** selection/lifecycle semantics; must not смешивать без документации.

## `@Bean` method as factory contract

**Must treat as public surface:** bean type, name, qualifiers, ordering, back-off.

**Must not:** менять method name без анализа bean name impact.

## FactoryBean

**Must not:** использовать, если обычный `@Bean` или factory object достаточно.

Double identity factory/product, lifecycle, lookup syntax — extraneous complexity.

## Auto-configuration creation pipeline

```text
bind properties
→ validate external form
→ normalize domain configuration
→ resolve user extensions
→ create runtime graph
→ activate lifecycle
→ publish diagnostics
```

**Must:** каждый этап усиливает гарантии и имеет собственную ошибку.

## Back-off = передача права создания

`@ConditionalOnMissingBean` — **must** определить достаточный bean type и semantic contract replacement (metrics, lifecycle, thread safety).

**Must not:** nominal type match без hidden expectations.

## Customizer pattern

**Must define:**

- порядок customizers
- defaults до или после
- conflict policy
- можно ли удалить platform invariant
- builder type — public contract или external SDK
- behavior on exception
- observability effective result

**Must not:** customizer как обход всех invariants без version-coupled extension zone.

## ObjectProvider

**Must:** deterministic selection inside auto-configuration.

**Must not:** expose `ObjectProvider` in application-facing API — container semantics leak.

## Partial graph

**Must not:** случайный partial capability graph.

**Must:** atomic validated graph **or** explicitly independent optional sub-capabilities.

## Framework / serialization boundaries

**Must localize** Jackson/JPA/CDI/Binder constraints на adapter/DTO boundary.

**Must not:** framework annotations on public domain type без deliberate dependency contract.

**Must:** wire creation — отдельный contract; adapter для unknown values и schema evolution.

## Decision matrix

| Условие | Предпочтительная форма |
|---|---|
| Малый прозрачный value, одна форма | constructor / record |
| Несколько named variants | static factories / sealed variants |
| Много independent optional | builder |
| Required deps + optional tuning | factory/constructor entry + builder |
| Creation depends on runtime deps | factory object / DI |
| Open set implementations | registry / SPI |
| Resource acquire / I/O | `open` / `connect` factory |
| Container-managed capability | auto-config + injected interface |

**Must not:** «N параметров → builder» без semantic analysis.

## Creation Contract Matrix (шаблон)

### Создание и инварианты

| Entry point | Понятие | Required | Defaults | Инварианты после успеха |
|---|---|---|---|---|
| `TenantId.of` | identifier | raw string | нет | canonical, non-empty |
| `RetryPolicy.exponential` | variant | attempts, delay | max delay policy | valid schedule |
| `ExportPipeline.builder` | blueprint | exporter, queue | batch/flush | immutable complete spec |
| `RuntimeFactory.create` | runtime | normalized config | platform defaults | consistent graph |
| auto-configuration | capability | props/classpath | starter contract | ready by lifecycle policy |

### Операционная семантика

| Entry point | Side effects | Ownership | Failure |
|---|---|---|---|
| `TenantId.of` | нет | value copied | `IllegalArgumentException` |
| `RuntimeFactory.create` | threads/resources | runtime owns | creation exception + cleanup |
| auto-configuration | container | Spring + documented externals | startup diagnostics |

**Must:** выявлять места, где `create` скрывает разные гарантии.

## Анти-patterns (must not)

| Anti-pattern | Fix |
|---|---|
| Constructor + initialize | atomic factory / lifecycle type / container init |
| Builder для mutually exclusive modes | named factories |
| Static utility factory catalog | factories у понятия |
| Options bag | schema + typed model |
| Framework-shaped domain object | adapter boundary |
| Hidden singleton `getInstance()` | explicit lifecycle + scope |
| Factory returns lazy as ready | rename/document or `connect` |
| Customizer bypasses all invariants | version-coupled extension contract |
