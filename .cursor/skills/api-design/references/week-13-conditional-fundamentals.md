# Week 13 — Conditional configuration: fundamentals

## Контекст

Conditions и back-off — формальный протокол между стартером, приложением и Spring container, а не набор удобных annotations.

**Ключевой тезис:** back-off — обещание распознавать поддерживаемые пользовательские решения и не конкурировать с ними за владение runtime graph.

## Функция сборки

```text
F(classpath, environment, registry, context kind, platform state)
  -> registered bean definitions
   | explained non-registration
   | failure
```

Допустимые исходы: platform default; user replacement; slice off (missing class / property); ambiguity → fail; condition miss с диагностикой.

**Must-not:** трактовать condition как случайный `if`.

## Детерминизм

Одинаковые входы → одинаковый bean graph и одинаковые причины решений.

**Must-not:** зависеть от неявного порядка component scanning, ранней инициализации singleton или случайного bean name.

Детерминизм нужен для объяснения активации, сравнения релизов, локализации regression, migration guarantee и классификации minor/major изменения condition.

## Координаты решения

| Координата | Примеры | Что выражает |
|---|---|---|
| Classpath | SDK, API, driver class | техническая возможность |
| Properties | enabled, mode, endpoint | явное намерение |
| Bean registry | type, name, annotation | user-provided реализация |
| Context kind | servlet, reactive, non-web | среда исполнения |
| Context hierarchy | current, ancestors, all | область видимости |
| Candidate semantics | primary, fallback, autowire | однозначность выбора |
| Resource/platform | resource, JNDI, threading | внешняя предпосылка |

**Must-not:** смешивать classpath («можем») и property (`enabled=false` — «хотим») в одну семантику.

## Наблюдаемая поверхность

Решение наблюдается через: beans (наличие/тип/порядок), startup success/failure, condition evaluation report, `/actuator/conditions`, debug logs, side effects ранней инициализации, metadata и docs.

Изменение condition может быть Java-совместимо и behavioral-breaking.

## Back-off как публичный контракт

Четыре обязательных элемента:

| Элемент | Вопрос |
|---|---|
| Target | какую platform definition подавляет user solution |
| Recognition rule | по какому признаку решение — замена |
| Scope | current context или + ancestors |
| Granularity | один bean, slice или вся auto-configuration |

Фраза «backs off» неполна без этих элементов.

### Back-off ≠ fallback

| | Back-off | Fallback |
|---|---|---|
| Суть | platform default **не регистрируется** | низкоприоритетный candidate при injection |
| Graph | definition отсутствует | bean существует |
| Риски @Fallback alone | — | collection injection, lifecycle, metrics, name lookup, смена semantics |

**Must-not:** заменять ownership design комбинацией `@Fallback` / `@Primary`.

## Принцип неинвазивности

Добавление явно поддерживаемого пользовательского решения **не должно** порождать конкурирующий platform default или менять несвязанные slices.

**Must-not:** любой bean похожего типа автоматически считать заменой — слишком широкое условие отключит инфраструктуру.

**Must:** документировать поддерживаемые seams (заменить transport, предоставить strategy, customizers, primary client, отключить slice). Неподдерживаемое вмешательство во внутренние beans не становится контрактом.

## Жизненный цикл вычисления

Conditions вычисляются при обработке configuration classes. Registry содержит **уже известные** definitions, не будущий полный context.

Bean condition отвечает: «есть ли подходящая definition среди уже известных текущему поиску?»

Поэтому bean conditions — прежде всего на auto-configuration; application config обычно раньше; порядок между auto-configurations задаётся явно.

Если condition зависит от bean, регистрируемого **позже**, default создаётся ошибочно. Исправление: явный ordering, стабильный marker, объединение slice, другой ownership protocol.

## Registration order ≠ creation order

| Связь | Механизм |
|---|---|
| Condition должен увидеть definition | auto-configuration ordering |
| Bean требует другой для создания | constructor / factory-method dependency |
| Init раньше без data dependency | `@DependsOn` с обоснованием |
| Execution order | `Ordered` / `@Order` / domain ordering |

`@AutoConfigureAfter` помогает `@ConditionalOnMissingBean` увидеть definitions; **не** гарантирует runtime creation order.

**Must-not:** чинить runtime race через ordering annotation, влияющую только на bootstrap.

## Топология conditions

Слои: coarse-grained возможность (class) → намерение (property) → replacement (missing bean).

```java
@AutoConfiguration
@ConditionalOnClass(AcmeClient.class)
@EnableConfigurationProperties(AcmeProperties.class)
class AcmeAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "acme", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    AcmeService acmeService(AcmeClient client, AcmeProperties properties) {
        return new DefaultAcmeService(client, properties);
    }
}
```

| Placement | Blast radius |
|---|---|
| Class-level | весь slice + nested |
| Method-level | одна definition |

Class-level — prerequisites всего slice. Replacement — чаще на bean method или узком nested configuration.

## Ключевые тезисы

- Conditions — детерминированная функция входов, не локальные if.
- Back-off требует Target, Recognition, Scope, Granularity.
- Back-off ≠ `@Fallback`.
- Неинвазивность ограничена документированными seams.
- Bean conditions видят частичный registry; ordering — часть корректности.
- Registration graph ≠ creation graph.
- Condition topology задаёт blast radius.
