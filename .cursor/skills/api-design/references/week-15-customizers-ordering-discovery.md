# Week 15 — Customizers, ordering, discovery

## Контекст

Augmentation и customization требуют algebra композиции, ownership borrowed object, детерминированного order и явной cardinality. Discovery через Spring injection — часть Extension API.

## Augmentation

Каждый extension добавляет поведение, не заменяет остальных (enrichers, interceptors, tag providers, hints registrars).

```text
result = fold(extensions, seed, combine)
```

Ответить: sequential vs independent; order matters?; duplicates?; stop chain?; last-write-wins vs conflict failure?; remove platform contribution?; snapshot at startup vs dynamic.

Независимые contributions → immutable merge. Sequential mutation → order-sensitive customizer chain.

## Customizer

```java
@FunctionalInterface
public interface AuditClientCustomizer {
    void customize(AuditClientSpec.Builder builder);
}
```

Документировать: когда properties/defaults уже применены; invariants ещё не проверены; сколько вызовов; conflicting writes; retention builder; exception; supported methods как extension API.

Functional interface: второй abstract method = source break. Контекст — immutable parameter object, не расширяющаяся сигнатура.

## Typed specification vs SDK builder

`void customize(ThirdPartyClientBuilder builder)` экспортирует SDK type → upgrade SDK = compat event; unsupported methods; bypass invariants; retain after construction; AOT/native наследует SDK.

Предпочтительнее platform-owned options + adapter в SDK. SDK builder — только явно нестабильный low-level seam.

## Ownership объекта

```text
starter owns editor
  -> callback borrowed reference
  -> mutate during invocation only
  -> must not retain
  -> starter validates and freezes
```

Narrow interface; no build method; copy to immutable after callbacks; no same mutable at runtime; tests на late mutation.

Customizer **must-not**: threads, resources, shutdown hooks.

## Порядок слоёв

```text
properties -> platform defaults -> user customizers -> validation -> build
```

| Слой | Роль |
|---|---|
| Hard invariant | не изменяем |
| Platform default | при отсутствии решения |
| Configuration property | deployment intent |
| Extension customizer | programmatic |
| Final normalization | канонизация |
| Validation | проверка effective spec |

Security invariant может применяться **после** customizer и отклонять запрещённое — документировать честно.

## Ordered / @Order / @Priority

Меньшее значение = выше приоритет. Публиковать named constants/phases, ranges для apps:

```java
public final class AuditExtensionOrder {
    public static final int SECURITY = -1000;
    public static final int DEFAULT = 0;
    public static final int LAST = 1000;
}
```

Numeric order ≠ dependency «A before B».

### PriorityOrdered

Отдельная category — раньше обычных Ordered независимо от чисел. Для feature customizers обычно достаточно Ordered. PriorityOrdered — container infrastructure.

User `PriorityOrdered` может перескочить platform phases → own phase enum; separate internal/user chains; fixed invariants first.

### Equal order

Relative position при равном order **не** guarantee. Conflicting writes → forbid / unique id / phase graph / commutative / fail startup. Registration order — плохой tie-breaker.

## Collection injection

| Form | Semantics |
|---|---|
| `List<T>` | candidates + ordering |
| `Set<T>` | uniqueness; order менее ясен |
| `Map<String,T>` | key = bean name |
| `T[]` | ordered array |
| `Collection<T>` | без сильного обещания формы |

**Must-not:** `Map<String,T>` как public model, если bean name ≠ domain identity. Injected collection ≈ snapshot при создании consumer — не dynamic registry.

## ObjectProvider

Lazy discovery: get when needed; absence; unique; ordered stream. Optional dependency / lazy proxy — явные semantics.

Single-valued selection: один candidate / primary; ambiguity = documented fail или policy.

## @Primary / @Fallback / qualifier

Selection axes, не ownership. Qualifier = semantic role (`@AcmeOutbound`), не bean id.

Bean name ≠ domain id. Generics как qualifier — документировать erasure/proxy limits. Exact `@Bean` return type влияет на condition matching. FactoryBean — dual identity (factory vs object).

## Cardinality как API

Формально: `0..1` / `1` / `0..N` / `1..N`. Duplicate identity policy. Ambiguity outcome. Collection vs single-owner role не смешивать на одном type без marker.

## Ключевые тезисы

- Composable algebra и conflict policy обязательны.
- Platform-owned editor, не SDK builder.
- Borrowed ownership + freeze после callbacks.
- Order dimension назван; equal-order не registration race.
- Cardinality и selection — публичный контракт.
