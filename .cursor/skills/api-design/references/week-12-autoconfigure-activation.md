# Week 12 — Auto-configuration: activation и conditions

## Контекст

Auto-configuration — детерминированный протокол активации и backoff, не набор annotations.

## AutoConfiguration.imports

**Must:** каждый candidate в:

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Добавление строки — изменение effective API: новый candidate оценивается во всех applications с artifact на classpath.

**Must-not:** `spring.factories` для EnableAutoConfiguration в modern starter.

`spring.factories` — только для других infrastructure extensions.

## Granularity auto-configuration

**Must-not:** один giant class на всю технологию — десятки conditions, неявный порядок.

**Must-not:** один class на каждый bean — ordering graph не обозрим.

Granularity по **capability и activation boundary**:

```text
FeatureCoreAutoConfiguration
FeatureWebMvcAutoConfiguration
FeatureWebFluxAutoConfiguration
FeatureActuatorAutoConfiguration
```

Каждый class: один reason to match, один связный output graph.

## Activation contract

Модель candidate как функция:

```text
Output bean graph = F(classpath, environment, existing beans, application type, resources)
```

Для каждой auto-configuration **must** фиксировать:

- обязательные classes
- properties и defaults
- required/missing beans
- допустимый application type
- порядок относительно других candidates
- создаваемые bean types и names
- backoff result
- failure behavior при partial prerequisites

### Activation table (шаблон)

| Classpath | Property | User bean | Результат |
|---|---|---|---|
| dependency отсутствует | любое | отсутствует | slice не активен, context стартует |
| dependency присутствует | disabled | отсутствует | feature beans отсутствуют |
| dependency присутствует | enabled/default | отсутствует | default graph создан |
| dependency присутствует | enabled/default | присутствует | backoff или композиция |
| dependency несовместима | enabled | отсутствует | ранний actionable failure |

**Must:** таблица **до** code. Исходная модель для ApplicationContextRunner tests.

## Conditions как executable protocol

`@Conditional...` влияет на global bean definition graph, не только один method.

| Категория | Назначение |
|---|---|
| class conditions | технологическая доступность |
| bean conditions | composition, backoff |
| property conditions | operator/application intent |
| web conditions | execution models |
| resource conditions | external artifacts |
| custom conditions | domain capability |

**Must-not:** custom condition читает пять properties, загружает class, проверяет filesystem, ищет bean name — невозможно тестировать и объяснить.

**Must:** композиция простых predicates + отдельная runtime validation сложных invariants.

## Class conditions и optional types

Spring Boot читает annotation metadata через ASM — top-level `@ConditionalOnClass(SomeClient.class)` может обработаться без загрузки type.

**Must-not:** optional type в method signature configuration class, который JVM загрузит раньше condition.

### Изолированный slice (безопасная форма)

```java
@AutoConfiguration
public final class FeatureAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.vendor.VendorClient")
    static class VendorClientConfiguration {

        @Bean
        VendorClientAdapter vendorClientAdapter(VendorClient client) {
            return new VendorClientAdapter(client);
        }
    }
}
```

Composed meta-annotations: **must** string name, не прямой class reference.

## Bean conditions и backoff

`@ConditionalOnMissingBean` — **полноценный SPI**. **Must** определить:

- replacement type
- search scope в context hierarchy
- допустимое количество beans
- qualifiers
- dependent defaults, которые тоже отступают
- кто валидирует incomplete user graph

**Must-not:** `@ConditionalOnMissingBean` на каждый helper — internal bean становится случайной точкой расширения.

### Максимально точный return type

```java
@Bean
@ConditionalOnMissingBean
DefaultPolicyEngine policyEngine(...) {
    return new DefaultPolicyEngine(...);
}
```

Interface return может скрыть capabilities от condition evaluation. Concrete return type — visible container surface; stability осознанно.

### Order dependency

Bean conditions зависят от порядка регистрации. **Must:** `@ConditionalOnMissingBean` в auto-configuration, обрабатываемой **после** user-defined beans.

**Must-not:** в application configuration или library component scanning — order-sensitive behavior.

## Property conditions

Три роли property — **must-not** смешивать:

| Роль | Пример |
|---|---|
| Feature intent | enable/disable capability |
| Configuration value | timeout, pool size |
| Implementation choice | provider, mode |

### matchIfMissing

Совпадение при отсутствии property → feature активируется во **всех** apps без настройки. Самый широкий behavioral decision.

**Must have:** безопасный default, bounded resource cost, понятный backoff, no destructive side effects, upgrade test, migration note при изменении.

### SpEL

**Must-not:** `@ConditionalOnExpression` для архитектурного выбора без необходимости — bean reference может вызвать раннюю инициализацию.

## Ordering auto-configurations

`before`/`after`, `@AutoConfigureBefore`/`@AutoConfigureAfter` — порядок применения candidates.

**Must-not:** ordering как замена dependency injection. Bean B требует A → constructor parameter точнее.

### Name-based ordering

`afterName`/`beforeName` — coordination с optional auto-configuration без compile dependency.

**Must:** строковая identity — contract, test с обеими сторонами на classpath.

### Replacement mapping

При переносе class:

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.replacements
```

Class identity auto-configuration — инфраструктурный compatibility surface.

## Definition order ≠ creation order

| Механизм | Влияет на |
|---|---|
| Auto-configuration ordering | visibility during condition evaluation |
| Dependencies, lifecycle, `@DependsOn` | instantiation order |

**Must-not:** lifecycle порядок через случайную регистрацию `@Bean` methods.

Runtime start/stop sequence → orchestration component, `SmartLifecycle`, explicit dependency, lifecycle coordinator.

## Startup cost

Auto-configuration исполняется во всех apps с artifact, даже если feature не активна.

**Must-not:** дорогие classpath scans, resource enumeration, Environment traversal, early object creation.

Autoconfigure annotation processor → metadata для ранней фильтрации. Generated file — publication verification.

### proxyBeanMethods = false

Lite mode уменьшает CGLIB, проще для AOT. **Must-not:** механически — direct inter-bean calls в lite mode создают новые instances.

### Lazy

**Must-not:** lazy как универсальная оптимизация для critical platform infrastructure — переносит cost и failures на first use, ухудшает deploy safety.

## Ключевые тезисы

1. Auto-configurations через AutoConfiguration.imports, не component scanning.
2. Candidate granularity — capability и activation boundary.
3. Condition set — executable container protocol.
4. Optional type изолирован в dependency metadata **и** при class loading.
5. `@ConditionalOnMissingBean` публикует replacement seam.
6. Ordering definitions ≠ bean creation order.
7. `matchIfMissing=true` — широкий blast radius.
8. Auto-configuration class identity — contract для exclusions/ordering.
