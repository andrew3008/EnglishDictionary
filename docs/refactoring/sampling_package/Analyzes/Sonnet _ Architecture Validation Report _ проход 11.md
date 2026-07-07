<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# да, дай

Да. Ниже — **жёсткий production-grade mapping** под более сильную структуру:

```text
space.br1440.platform.tracing.core.sampling
├── model
├── policy
├── engine
└── config
```


## 1. Точный mapping классов

| Current class | Target package | Visibility | Why |
| :-- | :-- | :-- | :-- |
| `SamplingPolicyRequest` | `model` | `public` | Immutable input model, часть runtime contract. |
| `SamplingPolicyDecision` | `model` | `public` | Immutable decision output, внешний контракт. |
| `SamplingPolicyDecisionType` | `model` | `public` | Enum доменного решения. |
| `SamplingPolicyReason` | `model` | `public` | Stable reason bridge to platform sampling reasons. |
| `ParentContextState` | `model` | `public` | Domain enum, не инфраструктура. |
| `SamplingPolicySnapshot` | `model` | `public` | Immutable compiled policy state. |
| `RouteRatioPrefix` | `model` | `package-private` если можно сломать API; иначе `public` временно | Это compiled detail, не часть SPI. |
| `SamplingPolicyRule` | `policy` | `public` | Rule SPI / domain contract. |
| `KillSwitchPolicyRule` | `policy` | `public` | Production rule. |
| `HardDropPolicyRule` | `policy` | `public` | Production rule. |
| `ForceHeaderPolicyRule` | `policy` | `public` | Production rule. |
| `QaTracePolicyRule` | `policy` | `public` | Production rule. |
| `ParentSampledPolicyRule` | `policy` | `public` | Production rule. |
| `RouteRatioPolicyRule` | `policy` | `public` | Production rule. |
| `DefaultRatioPolicyRule` | `policy` | `public` | Production rule, never abstains. |
| `TraceIdRatioDecision` | `policy` | `package-private` ideal, or `public final` only if needed | Это algorithmic helper, рядом с rules логичнее всего. |
| `SamplingPolicyRuleNames` | `policy` | `package-private` | Внутренняя метаинформация для rules. |
| `SamplingPolicyEngine` | `engine` | `public` | Runtime orchestrator. |
| `SamplingPolicyConfig` | `config` | `public` | Normalized config DTO. |
| `SamplingPolicyConfigValidator` | `config` | `public` | Unified validation logic. |
| `SamplingPolicySnapshotFactory` | `config` | `public` | Compiles config into snapshot. |

## 2. Почему именно так

### `policy`, а не `rule` и не `ratio`

`policy` лучше отражает смысл package boundary:

- там живут не только правила, но и алгоритм probabilistic decisioning;
- там же остаются rule names;
- это слой policy semantics, а не просто набор классов-правил.

`ratio` я бы не выделял вообще:

- он слишком узкий;
- даёт лишнюю глубину;
- делает архитектуру более “нарезанной”, чем нужно для маленького core.


### `model`

`model` должен содержать только immutable domain objects:

- request,
- decision,
- reason,
- snapshot,
- enums.

Никакой нормализации, валидатора, фабрик и engine logic.

### `config`

`config` должен стать настоящим configuration-time слоем:

- config DTO,
- validator,
- snapshot factory.

Это убирает смешение ответственности из `SamplingPolicySnapshot.fromConfiguration(...)`.

### `engine`

`engine` — только orchestration:

- порядок rules,
- evaluation chain,
- abstain fallback semantics.

Никакой конфигурации и никакой нормализации.

***

## 3. Зависимости между пакетами

Допустимый граф:

```text
model   -> nothing inside sampling
policy  -> model
engine  -> model, policy
config  -> model
config  -> no dependency on policy or engine
```


### Дополнительно:

- `policy` может зависеть от `engine` — **нет**, лучше не надо.
- `model` не должен знать о `policy`, `engine`, `config`.
- `config` не должен знать о rules вообще.
- `TraceIdRatioDecision` лучше не делать зависимым от `model`.


## 4. Где будут проблемы

### Проблема 1: `TraceIdRatioDecision` package-private

Если он в `policy`, все rule classes смогут использовать его без боли. Это лучше, чем отдельный `ratio` пакет.

### Проблема 2: `SamplingPolicySnapshotFactory`

Factory должна компилировать только snapshot.
Если она начнёт собирать engine — это уже неправильный слой.

### Проблема 3: `SamplingPolicySnapshot.fromConfiguration(...)`

Её надо оставить как compatibility facade:

- сначала делегировать в `SamplingPolicySnapshotFactory`;
- потом постепенно депрецировать;
- потом удалить только в major version.

***

## 5. Какие классы должны остаться compatibility facade

### Обязательно оставить facade:

- `SamplingPolicySnapshot.fromConfiguration(...)`


### Желательно оставить facade на время миграции:

- root-package `SamplingPolicySnapshot`
- root-package `SamplingPolicyEngine`
- root-package rule classes

Если перенос FQN уже начался, старые root-package классы должны работать как thin wrappers/deprecated aliases, иначе это будет слишком резкий слом.

***

## 6. ArchUnit rules

### Base rules

```java
noClasses().that().resideInAPackage("..core.sampling..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "io.opentelemetry..",
        "org.springframework..",
        "javax.management.."
    );
```


### Layer rules

```java
noClasses().that().resideInAPackage("..core.sampling.model..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "..core.sampling.policy..",
        "..core.sampling.engine..",
        "..core.sampling.config.."
    );
```

```java
noClasses().that().resideInAPackage("..core.sampling.config..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "..core.sampling.policy..",
        "..core.sampling.engine.."
    );
```

```java
classes().that().resideInAPackage("..core.sampling.engine..")
    .should().onlyDependOnClassesThat().resideInAnyPackage(
        "..core.sampling.model..",
        "..core.sampling.policy..",
        "java.."
    );
```

```java
classes().that().resideInAPackage("..core.sampling.policy..")
    .should().onlyDependOnClassesThat().resideInAnyPackage(
        "..core.sampling.model..",
        "java.."
    );
```


### Optional stricter rule

```java
noClasses().that().resideOutsideOfPackage("..core.sampling.policy..")
    .and().areNotTestClasses()
    .should().dependOnClassesThat().resideInAPackage("..core.sampling.policy..");
```

Если хочешь держать `TraceIdRatioDecision` package-private, лучше допустить доступ только из `policy`.

***

## 7. Что тестировать до рефакторинга

Минимальный обязательный набор:

- `QaTracePolicyRuleTest`
- `TraceIdRatioDecisionTest`
- fractional ratio tests для `DefaultRatioPolicyRule`
- fractional ratio tests для `RouteRatioPolicyRule`
- `SamplingPolicySnapshot.fromConfiguration` invalid entry tests
- snapshot sort / longest-prefix-first tests
- `SamplingPolicyDecision` constructor validation tests
- engine order test
- otel adapter parity test
- concurrent runtime update test

***

## 8. Моя финальная рекомендация

Для этого кода я бы шёл так:

### Лучший target

```text
model / policy / engine / config
```


### Почему

- чище, чем `model / rule / engine / config / ratio`;
- меньше пакетов;
- меньше ложной микродекомпозиции;
- `policy` лучше отражает смысл sampling domain;
- `ratio` как отдельный package не нужен.


### Что это означает practically

- `TraceIdRatioDecision` держим в `policy`;
- `ratio` не создаём;
- `config` становится полноценным compile/validate layer;
- `fromConfiguration` становится facade, а не core responsibility.

***

Если нужно, следующим сообщением могу дать **конкретный phased migration plan с файлами, в каком порядке переносить классы, и где именно поставить ArchUnit guardrails**, чтобы это можно было сразу отдавать в PR-план.

