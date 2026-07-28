# Week 13 — Classpath and bean conditions

## Контекст

Classpath conditions моделируют техническую возможность. Bean conditions моделируют ownership и зависимость от definitions. Смешение осей — частый дефект starter API.

## Classpath conditions

`@ConditionalOnClass` / `@ConditionalOnMissingClass` — возможность загрузить integration API.

**Must-not:** выражать пользовательское намерение через classpath (транзитивное наличие ≠ желание включить).

```text
integration active =
    required classes present
    AND feature enabled
    AND collaborators resolvable
```

Classpath — только первый член.

### Marker class

Marker **must** быть: необходимым для implementation; стабильным; специфичным; загружаемым без optional transitive types.

Слишком слабый: общий interface из широкой библиотеки. Слишком хрупкий: внутренний vendor class. Предпочтителен минимальный public API type.

## Изоляция optional types

Class-level `@ConditionalOnClass` обычно безопасен. Optional type в **signature** bean method / eagerly inspected месте → `NoClassDefFoundError` до expected outcome.

**Must:** optional types во вложенной configuration:

```java
@AutoConfiguration
class AcmeAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.vendor.OptionalClient")
    static class VendorConfiguration {

        @Bean
        VendorAdapter vendorAdapter(com.vendor.OptionalClient client) {
            return new VendorAdapter(client);
        }
    }
}
```

Общий bootstrap layer **must-not** иметь signatures с optional types.

## Composed annotations

Для meta-annotations Spring Boot **не** гарантирует ту же безопасную обработку class-valued attributes. Если type может отсутствовать — строковое `name`.

Composed annotation — отдельный API: стабильная semantics, понятная диагностика, имя описывает prerequisite/режим, не скрывает policy.

## Bean conditions — не runtime query

`@ConditionalOnBean` / `@ConditionalOnMissingBean` / `@ConditionalOnSingleCandidate` работают с bean definitions и candidate semantics, не с `getBean()`.

Подходит: user strategy, prerequisite auto-config, единственный injectable client, marker bean.

**Must-not:** проверять внешнее состояние, валидность property или remote readiness.

Учитывать: autowire candidate / default candidate flags — тестировать на baseline, не гадать.

## @ConditionalOnMissingBean

Самая безопасная форма — semantic replacement type:

```java
@Bean
@ConditionalOnMissingBean(AcmeService.class)
DefaultAcmeService acmeService(...) { ... }
```

Контракт: любой assignable к `AcmeService` владеет ролью.

Нулевая форма выводит target из return type — return type одновременно publication type и replacement key. Его смена меняет back-off contract.

### Точность return type

| Ошибка | Эффект |
|---|---|
| Слишком конкретный (`DefaultAcmeService`) | user `AcmeService` не подавляет → две реализации |
| Слишком общий (`Object`) | capability невидима для type discovery |

**Must:** return type достаточно конкретен для discovery; replacement key = поддерживаемая роль. Если расходятся — target явно.

```java
@Bean
@ConditionalOnMissingBean(AcmeService.class)
DefaultAcmeService acmeService(...) { ... }
```

## Измерения matching

| Измерение | Когда уместно | Риск |
|---|---|---|
| Type | роль = Java abstraction | unrelated bean того же широкого типа |
| Name | имя — published Spring contract | rename = breaking |
| Annotation | несколько типов + semantic marker | annotation = SPI |
| String type | class может отсутствовать | опечатки поздно |

Bean name для back-off — только если имя сознательно опубликовано. Внутренний factory method name → accidental contract.

Несколько ролей одного типа → qualifier / отдельная abstraction, не тайное строковое имя.

## Candidate filtering

`ignored` / `ignoredType` — исключить wrappers. Длинный ignored list — сигнал слишком широкого replacement key.

Предпочтительная коррекция: точный role interface, marker annotation, wrappers не на том же public type, другой slice, разделение collection vs single-owner role.

## Parameterized containers

`parameterizedContainer` — `Container<AcmeService>` как доступность `AcmeService`.

**Must** опубликовать: какие containers распознаются; lazy provider = замена?; multi-generic; proxy/factory metadata; тесты на baseline.

**Must-not:** любой `Supplier<T>` считать заменой только из-за generic.

## Context hierarchy

| SearchStrategy | Область |
|---|---|
| CURRENT | только текущий |
| ANCESTORS | только parents |
| ALL | current + ancestors |

Default для `@ConditionalOnMissingBean` — ALL (parent подавляет child default).

Выбор: shared client в parent → ALL; per-web-context adapter → CURRENT; prerequisite только из parent → ANCESTORS.

Одно-context tests недостаточны для hierarchy bugs.

## @ConditionalOnBean

Положительная зависимость от prerequisite definitions. Existence ≠ unambiguous injection.

Если нужен ровно один dependency → `@ConditionalOnSingleCandidate`. Qualifier selection **must** совпадать в condition и injection point.

## @ConditionalOnSingleCandidate

Совпадает при одном candidate или одном primary среди нескольких.

Silent non-registration при ambiguity **не всегда** правильна — при явном enable feature молчаливое отсутствие facade маскирует ошибку.

Policy варианты: skip slice; fail fast; потребовать qualifier/property; routing facade для collection. Annotation реализует detection, не продуктовую policy.

## @Primary, @Fallback, qualifiers

Три оси ≠ back-off:

| Механизм | Вопрос |
|---|---|
| `@Primary` | кого предпочесть среди candidates |
| `@Fallback` | низший приоритет, если нет regular |
| Qualifier | сузить по семантической роли |
| Back-off | регистрировать ли platform default вообще |

Qualifier = роль (`@AcmeOutbound`), не bean id. Condition и injection **must** проверять один qualified set.

## Ambiguity как отдельное состояние

```text
0 candidates            -> absent
1 candidate             -> resolvable
N + winner              -> resolvable by selection
N no winner             -> ambiguous
```

| Состояние | Policy |
|---|---|
| Absent | default или skip optional |
| Resolvable | user/platform candidate |
| Ambiguous | fail fast / Primary / qualifier / aggregate |
| Invalid | fail с validation |

**Must-not:** тихо отключать downstream при ambiguity.

## Ключевые тезисы

- Classpath = возможность; property = намерение; bean = ownership.
- Optional types изолируются nested configuration.
- Replacement key и return type согласованы явно.
- Name-based back-off — только published names.
- Ambiguity ≠ absence.
- Primary/Fallback/qualifier не заменяют back-off.
