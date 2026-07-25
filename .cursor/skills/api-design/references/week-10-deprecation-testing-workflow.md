# Week 10 — Deprecation, removal, test matrix, workflow

## Контекст

Эволюция API — управляемый протокол: deprecation, evidence-based removal, compatibility test matrix, release gates.

## Deprecation как протокол

`@Deprecated` — только compiler signal. Полноценная deprecation включает:

| Элемент | Обязательность |
|---|---|
| `since` | must |
| `forRemoval` | только при реальном намерении и horizon |
| Javadoc `@deprecated` с причиной | must |
| конкретная replacement API | must |
| semantic differences replacement | must |
| migration example | must |
| runtime/config diagnostic (если compiler не видит usage) | must для non-Java surfaces |
| owner и removal criteria | must |

```java
/**
 * @deprecated Use {@link ClientOptions#requestTimeout()}.
 * The replacement uses a total request budget, not a socket timeout.
 */
@Deprecated(since = "3.4", forRemoval = true)
public Duration timeout() { ... }
```

### Deprecation без replacement

Документация **must** объяснить: как решается use case теперь, почему прежний path опасен, operational impact отказа.

### Non-Java surfaces

Property, bean name, metric, metadata entry — без compiler warning. Нужны:

- configuration metadata deprecation
- startup warning с deduplication
- condition report / failure analyzer
- stable diagnostic code
- usage inventory
- documentation и examples без deprecated path

### Warning fatigue

**Must-not:** предупреждение, которое невозможно устранить или логируется на каждый request — consumers игнорируют diagnostics.

Signal **must:** actionable, bounded, достаточно рано.

## Removal и evidence

**Must-not:** «deprecated уже год» — не evidence.

### Evidence sources

- source/code search
- dependency graph
- provider registry
- config inventory
- runtime usage telemetry
- support tickets
- migration completion по release train

### Effective consumers

Reflection, class names в YAML, serialized objects, bean names, scripts — не обнаруживаются обычным Java search. Inventory **must** соответствовать surface.

### Removal gate — разрешено когда

- policy допускает version boundary
- replacement стабилен
- migration guide проверен
- supported consumers/providers мигрировали
- rollback не требует возвращения удалённого data writer
- compatibility baseline обновляется осознанно
- release notes называют impact, не только symbol list

## Compatibility test matrix

### Четыре базовых Java lanes

| Lane | Сборка | Доказывает |
|---|---|---|
| old binary → new runtime | fixture не перекомпилируется | binary caller |
| old source → new compile | source компилируется заново | source |
| old provider → new framework | старый SPI binary/source | implementor |
| new implementation → old consumer expectations | contract tests | behavioral |

### Расширенная matrix для starter

- old/new property files
- user bean replacement
- zero/one/multiple providers
- optional dependency absent/present
- managed dependency set и supported overrides
- classpath и module path
- minimum и current Java
- JVM и заявленный AOT/native mode
- old/new serialized fixtures
- Actuator/metrics snapshot
- clean startup и upgrade startup с persisted/config state

### Behavioral fixtures

Snapshot списка methods **не проверяет** semantics. Scenarios:

- defaults, invalid inputs, concurrency, order
- failure translation, resource cleanup, backoff
- cancellation/timeout, unknown data/config values
- rollback после частичного upgrade

### Negative tests

**Must** проверять запрещённое:

- internal package не импортируется
- optional dependency не нужна base consumer
- deprecated path выдаёт diagnostic
- unsupported provider version отвергается рано
- duplicate candidates не выбираются случайно
- old reader не получает meaning-changing new payload

## Tooling и baselines

### API diff (Revapi, japicmp)

**Must:** CI против последнего **released** baseline, не соседнего commit.

Настройка: include/exclude packages, protected members, stability labels, synthetic/bridge policy, missing dependency handling, accepted difference с reason и expiry.

### Baseline discipline

**Must-not:** автоматически перезаписывать baseline candidate version после красного diff. Обновление baseline — отдельное review decision.

### Tool blind spots

Обычный checker **не знает:** documented behavior, property defaults, Spring conditions, bean names/order, serialized JSON, operational surfaces, dependency resolution, provider protocol.

Release gate = несколько специализированных проверок.

### Reproducible artifacts

Сравнивать **публикуемый** JAR/POM/module metadata. Source model может отличаться из-за annotation processing, weaving, shading, minimization.

## Change Classification Workflow

### Шаг 1. Surface и audience

Что меняется: caller API, SPI, subclass hook, config, bean, metadata, data, dependency, operations. Назвать consumer и owner.

### Шаг 2. Старые symbolic assumptions

Java: old binary name/descriptor/access/hierarchy. Spring: old key/type/default/condition/bean selection. Data: old schema и reader assumptions.

### Шаг 3. Dimensions независимо

**Must-not:** один итог `compatible=true`.

```text
binary:       compatible / breaking / n.a.
source:       compatible / breaking / conditional
behavioral:   compatible / breaking / unknown
implementor:  compatible / breaking / n.a.
migration:    direct / bridge / coordinated / impossible
```

### Шаг 4. Release strategy

- patch с contract-preserving fix
- minor additive evolution
- deprecate + bridge
- release-train coordinated change
- major migration
- новый parallel API/SPI
- **отказ от изменения**

### Шаг 5. Evidence

Каждое утверждение → проверка: diff, recompilation, mixed binary test, provider kit, context matrix, schema fixture, operational snapshot, migration rehearsal.

## Migration guide

**Must contain:**

- affected audience
- old/new semantics
- prerequisite versions
- пошаговый order
- code/config before-after
- dual-operation period
- observability проверки
- rollback boundary
- known incompatibilities
- removal deadline
- **отдельный путь для callers и implementors**

**Must-not:** mechanical rename без semantic warning (replacement с другим timeout meaning, ownership, failure mode → тихий production regression).

Guide **must** быть выполнен на sample application/provider. Acceptance: compile + startup + behavior + operations + rollback.

## Compatibility policy

Policy **must** публиковать:

- какие surfaces поддерживаются
- какие dimensions гарантируются
- minimum Java/Spring/dependency baselines
- duration deprecation
- правила experimental/incubating API
- SPI compatibility window
- data/config retention period
- поддерживаемые upgrade paths
- cadence major releases
- emergency security exception process

### Stability labels

`@Beta` без release policy не отвечает «можно ли удалить завтра». Internal annotation без access enforcement не предотвращает effective usage.

Разные окна для consumer API, provider SPI, persisted format.

## Анти-паттерны

| Анти-паттерн | Проблема |
|---|---|
| «API diff зелёный — совместимо» | игнорируются source, behavior, implementors, config, data, Spring |
| «Добавление всегда minor» | overload, enum variant, annotation default, bean candidate, property default |
| «Unchecked exception не API» | recovery logic зависит от meaning, type, retryability |
| «Default method починил SPI» | linking сохранён, semantic capability нарушена |
| «Major разрешает всё» | fleet migration, data rollback, independent providers |
| «Deprecated достаточно долго» | срок без usage evidence |
| «Properties — implementation details» | keys, defaults, units, precedence управляют production |
| «BOM гарантирует совместимость» | BOM выбирает versions; tests доказывают behavior |
| «Перекомпилируем всё» | не решает behavioral, data, config, provider |
| «Unknown values всегда игнорируем» | tolerant reader опасен для security/semantics |

## Контрольный чек-лист (release)

### Compatibility scenario

- [ ] old/new стороны и порядок обновления названы?
- [ ] source и compiled consumers разделены?
- [ ] caller и implementor audiences разделены?
- [ ] success criterion каждого scenario определён?
- [ ] compatibility envelope опубликован?

### Java binary/source

- [ ] binary names, descriptors, accessibility проверены?
- [ ] overloads протестированы на corpus call sites?
- [ ] generic erasure/bounds/bridges проверены?
- [ ] checked и documented unchecked exceptions оценены?
- [ ] constant inlining учтён?
- [ ] inheritance/protected surface проверен?
- [ ] default methods имеют conservative semantics?
- [ ] enum/sealed/record exhaustive consumers учтены?

### Evolution process

- [ ] SemVer к явно определённому public contract?
- [ ] deprecation owner, since, replacement, removal evidence?
- [ ] migration guide разделяет callers и implementors?
- [ ] rollback проверен?
- [ ] каждое compatibility утверждение имеет automated evidence?

## Ключевые тезисы

1. Deprecation — protocol с replacement, diagnostics, owner, evidence.
2. Removal разрешается данными об использовании, не возрастом warning.
3. Compatibility test matrix смешивает старые и новые стороны.
4. API diff — один gate среди нескольких.
5. Baseline обновляется решением, не автоматически.
6. Migration guide обязан разделять callers, implementors, data, operations.
7. Rollback проектируется до изменения data/config format.
8. Лучшее compatibility решение иногда — отказ публиковать новый surface.
