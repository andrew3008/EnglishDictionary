# Week 12 — Dependencies, AOT, testing, publication

## Контекст

Starter suite публикует dependency policy, reachability для AOT/native, verification matrix и publication contract.

## Dependency architecture

### Gradle roles (consumer perspective)

| Configuration | Consumer compile | Consumer runtime |
|---|---|---|
| `api` | да | да |
| `implementation` | нет | да |
| `compileOnly` | нет (producer only) | owner must provide |
| `runtimeOnly` | нет | да |
| `annotationProcessor` | build-time only | must-not publish |

### Пример autoconfigure module

```gradle
dependencies {
    api project(':acme-api')
    implementation project(':acme-core')
    api 'org.springframework.boot:spring-boot-autoconfigure'
    annotationProcessor 'org.springframework.boot:spring-boot-autoconfigure-processor'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'
    compileOnly 'com.vendor:vendor-client'
}
```

**Must:** решение по published metadata, не только локальному classpath. Если autoconfigure signatures не содержат Boot types — `spring-boot-autoconfigure` может не требоваться consumer compile.

### Starter dependency-only

```gradle
dependencies {
    api project(':acme-spring-boot-autoconfigure')
    api 'org.springframework.boot:spring-boot-starter'
    runtimeOnly 'com.vendor:vendor-client'
}
```

Vendor client как `api` — решение экспортировать vendor surface. **Must:** по умолчанию prefer stable abstraction или explicit direct dependency приложения.

## Optional dependencies — четыре слоя

Optional **must** выполнять все четыре:

- [ ] base artifact компилируется и загружается без неё
- [ ] published metadata не добавляет в base runtime
- [ ] auto-configuration safely backs off при отсутствии
- [ ] tests: negative classpath scenario

**Must-not:** одного `compileOnly` недостаточно — signature, static initializer, resource import могут загрузить type.

### Autoconfigure без technology JAR

Technology dependencies optional в autoconfigure → autoconfigure без технологии, conditions отступают. Starter добавляет типовую runtime dependency.

Разделение позволяет альтернативный starter dependency set.

### Giant optional graph

Десятки compile-only SDK + сотни conditions → вынести optional integration физически.

## BOM и release train

BOM оправдан при нескольких совместно релизимых artifacts.

**Must-not:** BOM исправляет dependency direction. Core → autoconfigure или starter экспортирует internal SDK — выравнивание versions не делает архитектуру правильной.

Корпоративный starter **must** публиковать: min Java, Spring Boot line, tested dependency set, user override policy, major migration order.

**Must-not:** starter тихо подменяет Spring Boot BOM без platform-level решения.

## AOT и native architecture

«Работает на JVM» **не доказывает** native compatibility.

**Must-not** в starter architecture:

- scan произвольных packages
- load class names из remote config после build
- создавать неизвестные proxy interfaces
- enumerate resources без hints
- runtime-only bean definition mutation
- reflection, скрытая от AOT engine

### Hints рядом с capability

`RuntimeHintsRegistrar` рядом с configuration slice, делающим dynamic operation reachable.

**Must:** unit tests через `RuntimeHintsPredicates`; заявленная native support — end-to-end build/run lane.

### Test matrix modes

JVM, AOT-on-JVM, native executable — разные failure surfaces. **Must** назвать modes явно.

`ApplicationContextRunner` — condition combinations. **Must-not:** заменять native-image test.

## Test architecture

| Слой | Доказывает | Подход |
|---|---|---|
| API/core | domain semantics | unit/contract |
| Autoconfigure | conditions, beans, backoff | context runners |
| Integration slice | optional classpath, framework mode | focused integration |
| Starter | published dependency experience | sample consumer / resolved graph |
| Operations | metrics, health, diagnostics | operational contract tests |
| AOT/native | reachability, generated graph | AOT/native lanes |

**Must-not:** `@SpringBootTest` как единственный test — один happy path, скрывает broken combinations.

### Coverage dimensions

```text
property: absent / true / false / invalid
dependency: present / absent / incompatible
user bean: absent / one / multiple
web: Servlet / reactive / non-web
Actuator: absent / present
parent context: absent / present
execution: JVM / AOT / native
Spring Boot baselines
```

Pairwise/risk-based selection явны; critical combinations обязательны.

## ApplicationContextRunner matrix

```java
private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        FeatureAutoConfiguration.class));
```

**Must:** один runner — одна combination; assertions на beans, names, properties, backoff, failure.

### FilteredClassLoader

```java
contextRunner
    .withClassLoader(new FilteredClassLoader(VendorClient.class))
    .run(context ->
        assertThat(context).doesNotHaveBean(VendorClientAdapter.class));
```

**Must:** успешный startup без linkage errors, не только отсутствие bean.

### Web contexts

`WebApplicationContextRunner`, `ReactiveWebApplicationContextRunner` для Servlet/reactive branches.

## Integration и black-box tests

### Sample consumer

External project зависит **только** от published starter (staging GAV).

Доказывает: resources, POM/GMM graph, processors не нужны runtime, auto-config через imports, default startup, documented override компилируется.

**Must-not:** test starter только через `@Import(FeatureAutoConfiguration.class)` — обходит discovery metadata.

### Upgrade fixture

Consumer с прежними properties и user beans + candidate release. Ловит defaults, bean selection, config aliases без Java API diff.

## Publishing architecture

Release artifact **must** включать:

- JAR contents
- POM, Gradle Module Metadata
- `AutoConfiguration.imports`, replacement mappings
- configuration metadata, autoconfigure metadata
- service/factory registrations
- native-image metadata
- sources/Javadoc по policy

Каждый элемент reproducible, сравнивается с baseline.

**Must-not:** executable starter JAR (bootJar с nested dependencies).

**Must:** release gate — starter в чистом consumer project, compile/runtime graph output.

## Starter Architecture Review

| Шаг | Действие |
|---|---|
| 1 | Adoption contract: one-line dep, enabled-by-default?, mandatory runtime, first public bean, disable/replace, startup без external system |
| 2 | Planes и modules: runtime/bootstrap/adoption; physical artifacts с owner, closure, contract |
| 3 | Activation function: classpath/properties/beans/app type → graph; test per row |
| 4 | Extension model: replacement, customizers, strategies, providers; multiplicity, ordering, failure |
| 5 | Non-functional: startup cost, bounds, shutdown, AOT/native, diagnostics, security, ops, conflicts, upgrade/rollback |

## Topology ADR

**Must** фиксировать:

- почему один/два/несколько modules
- alternatives отвергнуты
- mandatory vs optional dependencies
- API/SPI placement
- почему defaults безопасны
- supported override paths
- forbidden combinations
- topology evolution при новой integration
- automatic verification gates

| Решение | Gate |
|---|---|
| Starter dependency-only | published graph test |
| Vendor SDK optional в autoconfigure | filtered classloader test |
| Actuator отдельным slice | context tests with/without Actuator |
| Customizer collection | deterministic order test |

## Анти-паттерны

| # | Анти-паттерн |
|---|---|
| 1 | Starter как свалка dependencies |
| 2 | Component scan внутри auto-configuration |
| 3 | `@ConditionalOnMissingBean` на каждом bean |
| 4 | Один global `enabled` без раздельной semantics |
| 5 | Один giant auto-configuration |
| 6 | Properties object как service locator |
| 7 | Customizer без ordering contract |
| 8 | `@SpringBootTest` как единственный test |
| 9 | bootJar для library/starter |
| 10 | Модули ради диаграммы без dependency boundary |

## Контрольный чек-лист

### Architecture boundary

- [ ] runtime, bootstrap, adoption разделены?
- [ ] core не зависит от autoconfigure, не ищет ApplicationContext?
- [ ] physical modules имеют real boundary?
- [ ] starter — понятная dependency policy?

### Discovery и activation

- [ ] candidates через AutoConfiguration.imports?
- [ ] auto-config packages не сканируются?
- [ ] activation table с tests?
- [ ] optional types изолированы от base class loading?

### Dependencies и publication

- [ ] api/implementation = consumer contract?
- [ ] optional во всех четырёх слоях?
- [ ] processors не runtime?
- [ ] POM/GMM проверены external consumer?
- [ ] не bootJar?

### Evolution

- [ ] auto-configuration identity для exclusions/ordering?
- [ ] replacement mapping при перемещении?
- [ ] upgrade fixture со старыми properties?
- [ ] default changes — отдельный review?

## Ключевые тезисы

1. api/implementation — модель consumer contract.
2. Optional — согласованность build, bytecode, conditions, tests.
3. AOT/native требует reachability design и отдельных tests.
4. Context runners — combinations; black-box consumer — publication.
5. Published metadata эволюционирует с classes.
6. Starter Architecture Review начинается с contract tables.
7. topology ADR назначает каждому решению automatic evidence.
