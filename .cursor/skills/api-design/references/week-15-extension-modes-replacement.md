# Week 15 — Extension modes and replacement

## Контекст

Extension API — опубликованный протокол: кто вмешивается, в какой фазе, над каким объектом, в каком порядке и с какими последствиями. Не «Spring как-нибудь найдёт ваши beans».

## Extension contract

```text
extension contract =
    discovery + eligibility + cardinality + ordering
  + invocation phase + input/output ownership
  + failure policy + observability + compatibility guarantees
```

`Customizer<T>` описывает только форму callback. Cardinality, timing, retention, conflict, exception → startup — не менее публичны, чем signature.

Java contract и Spring container contract проектируются совместно (bean discovery, qualifier, order, proxy, conditions).

## Потребители

| Потребитель | Нужно | Риск |
|---|---|---|
| App developer | простой bean/callback | отключить весь starter |
| Platform module | композиция policies | циклы, порядок |
| Integration author | стабильный SPI | утечка SDK types |
| Spring container | types, qualifiers | ambiguity |
| SRE | объяснимый graph | расширение невидимо |
| Starter author | эволюция | сломать сторонние extensions |

«Можно объявить bean» ≠ «поддерживается». Supported seam — в Public Contract Inventory.

## Поверхности

| Поверхность | Пример | Обещает |
|---|---|---|
| Java API | Client, Request | прямое использование |
| SPI | CredentialProvider | реализацию потребителем |
| Callback API | ClientBuilderCustomizer | ограниченное вмешательство |
| Spring integration | type/name/qualifier/order | discovery/композиция |
| Operational | diagnostics, metrics, events | наблюдаемость |

Документировать только интерфейс недостаточно: `@Bean` + qualifier + `@Order` = часть API. Bean name может стать контрактом даже для internal type.

## Intended vs effective

Effective surface шире intended: beans by type/name; Primary; ordering; mutable builders; events; BPP; Multicaster replacement; reflection; Actuator bean names; scope/lazy/hierarchy; proxy/advisor.

Undocumented но production-tested вмешательство = compatibility risk. Легализовать или закрыть + дать альтернативу. Review начинается с inventory effective surface.

## Четыре режима

| Режим | Семантика | Seam |
|---|---|---|
| Replace | user владеет реализацией | bean replacement + back-off |
| Augment | несколько вкладов → chain/set | typed extension beans |
| Customize | последовательные изменения construction | customizer callback |
| Observe | реакция без владения результатом | listener/event |

Смешение режимов: replacement для interceptor отключает platform chain; event для обязательной мутации builder — async/absence/no return.

Формулировка: «заменяет X» / «добавляет Y» / «настраивает Z» / «наблюдает W». Несколько глаголов → разные seams.

## Принцип минимальной силы

```text
property < strategy interface < typed customizer
  < builder exposure < bean replacement
  < BeanPostProcessor < BeanFactoryPostProcessor
```

BPP/BFPP — расширения контейнера, не feature hooks для app developer.

## Replacement

User предоставляет реализацию; starter не создаёт конкурирующий default (`@ConditionalOnMissingBean`).

Полный contract: target type/name; search scope; lifecycle; capabilities; dependencies starter всё ещё даёт; diagnostics owner; поведение при нескольких user impls.

Граница replacement = граница resource ownership (exporter + scheduler + health не обязаны отступать вместе).

### Точность target

| Target | Плюс | Риск |
|---|---|---|
| Interface | простой | unrelated bean подавляет |
| Concrete | точный default | custom impl не распознаётся |
| Bean name | точечный | rename = break |
| Annotation marker | несколько типов | annotation = SPI |

### Back-off ≠ selection

Back-off решает, регистрировать ли default. Selection (`@Primary`/`@Fallback`/qualifier) решает, кого inject среди существующих. `@Fallback` alone не удаляет platform definition из graph/lifecycle/collections.

## Ключевые тезисы

- Extension = protocol, не только interface.
- Четыре режима не смешивать.
- Минимальная достаточная сила mechanism.
- Replacement target и ownership совпадают.
- Back-off ≠ Primary/Fallback.
