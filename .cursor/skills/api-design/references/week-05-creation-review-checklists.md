# Неделя 05 — Creation API Review и чек-листы

## Creation API Review — workflow

### Шаг 1. Инвентаризация entry points

**Must найти:** public constructors, static factories, builders, `toBuilder`, registries, providers, `@Bean`, auto-configuration, test fixtures.

**Must:** несколько путей к одному type → проверить расходящиеся defaults.

### Шаг 2. Классификация

value / entity / specification / runtime / handle / provider + ожидаемая готовность после creation.

### Шаг 3. Inputs

| Категория | Must разделить |
|---|---|
| required always | |
| optional with default | |
| required for variant only | |
| mutually exclusive | |
| derived | |
| injected environment | |

Conditional inputs → сигнал sum type, не universal constructor/builder.

### Шаг 4. Invariants и validation timing

Для каждого invariant: creation stage, exception/result, diagnostic.

**Must not:** повторная проверка после успешного creation.

### Шаг 5. Side effects, ownership, lifecycle

Что создаётся/захватывается; ownership transfer point; cleanup on failure; who closes result.

### Шаг 6. Defaults и эквивалентность путей

Constructor, factory, builder, YAML, auto-config — **same intent → same semantics?**

### Шаг 7. Evolution analysis

New required field, variant, implementation, async creation, framework adapter — impact on chosen form.

## Чек-лист: семантика создания

- [ ] Уровень готовности после успеха понятен?
- [ ] Итоговый type не требует undocumented init/validate/register?
- [ ] Form matches value/spec/runtime/handle?
- [ ] Blueprint отделён от live object?
- [ ] Side effects видны из имени/контракта?

## Чек-лист: constructor и factory

- [ ] Constructor — одна каноническая форма?
- [ ] Роли и порядок параметров очевидны?
- [ ] Variants — named factories?
- [ ] Factory names последовательны (`of`/`from`/`parse`/`open`)?
- [ ] Polymorphic/cached return не ломает identity expectations?

## Чек-лист: records

- [ ] Components = полное публичное значение?
- [ ] Compact constructor — локальные invariants?
- [ ] Mutable components defensive-copied?
- [ ] Equality после normalization documented?
- [ ] Evolution representation acceptable?

## Чек-лист: builder

- [ ] Builder решает optionality, не маскирует модель?
- [ ] Required inputs видны до `build()`?
- [ ] Conditional inputs без state explosion?
- [ ] Duplicate assignment, collection ordering, reuse defined?
- [ ] `build()` atomic valid immutable?
- [ ] Defaults = другие creation paths?
- [ ] Staged builder оправдан предотвращаемой ошибкой?

## Чек-лист: factory object и SPI

- [ ] Factory — capability с scope/thread safety/lifecycle?
- [ ] Spec не протекает implementation types?
- [ ] Registry: namespace, collision, freeze?
- [ ] ProviderContext minimal/stable?
- [ ] Contract tests для extension author?

## Чек-лист: ownership и ресурсы

- [ ] borrow/copy/take для каждого input?
- [ ] Close executors/clients/buffers defined?
- [ ] Failure creation — cleanup?
- [ ] Lazy/eager согласованы с readiness?
- [ ] Async: cancellation, partial effects?

## Чек-лист: Spring Boot

- [ ] App dev — ready capability, not internal graph?
- [ ] Properties → strong runtime model?
- [ ] `@Bean` names/types/qualifiers not accidental API?
- [ ] Back-off — понятная replacement responsibility?
- [ ] Customizer ordering и invariant boundaries?
- [ ] No partial graph?

## Контрольные вопросы (выборка)

1. Гарантии операции создания beyond allocation?
2. Когда constructor лучшая форма?
3. Как constructor связывает API с representation?
4. Variant factories vs overloaded constructors?
5. `of` / `from` / `parse` / `open` / `load`?
6. Result vs exception для creation failure?
7. Record shallow immutability?
8. JavaBean как boundary?
9. Contract builder properties?
10. Required values в entry point builder?
11. Staged builder criteria?
12. `withX` vs `toBuilder()`?
13. Injectable factory object when?
14. Registry rules?
15. ServiceLoader не решает?
16. Caching runtime vs interning values?
17. Ownership variants?
18. I/O in constructor?
19. Lazy vs ready client?
20. `@ConditionalOnMissingBean` и customizer?

## Итоговые тезисы

1. Creation form = semantic contract.
2. Constructor — одна каноническая прозрачная форма.
3. Static factory — variants, conversion, caching, hidden impl.
4. Record — stable full state description.
5. JavaBean — binding boundary only.
6. Builder — optionality, not wrong model fix.
7. Required inputs visible before `build()`.
8. Factory object — injectable when context-dependent.
9. Registry/SPI — explicit selection and diagnostics.
10. Ownership and cleanup — part of creation contract.
11. Auto-configuration — multi-stage public factory.
12. Canonical path links Java, config, lifecycle, diagnostics.

## Практическое задание

**Must produce:** Creation Contract Matrix + заключение: каноническая форма per level; classify entry points as convenience / SPI / adapter / accidental API.
