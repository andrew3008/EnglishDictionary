<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# да, напиши

Ниже — практический phased migration plan под структуру:

```text
space.br1440.platform.tracing.core.sampling
├── model
├── policy
├── engine
└── config
```


## Phase 0 — Tests first

Цель: зафиксировать текущее поведение до любых перемещений.

### Что добавить

- `QaTracePolicyRuleTest`.
- `TraceIdRatioDecisionTest`.
- fractional ratio tests для `DefaultRatioPolicyRule`.
- fractional ratio tests для `RouteRatioPolicyRule`.
- `SamplingPolicySnapshot.fromConfiguration` tests for invalid entries.
- snapshot sort / longest-prefix-first tests.
- `SamplingPolicyDecision` compact constructor validation tests.
- engine rule-order test.
- OTel adapter parity test.
- concurrent runtime update test.


### Риск

Минимальный. Это только защитные тесты, но именно они покажут, есть ли скрытые зависимости на неочевидное поведение.

### Rollback

Не нужен, production code не трогаем.

### Definition of Done

- Все текущие тесты зелёные.
- Новые тесты проходят.
- Зафиксированы:
    - invalid route entries behavior;
    - ratio parity;
    - rule order;
    - fallback semantics.

***

## Phase 1 — Introduce config layer

Цель: вынести конфигурационную логику из `SamplingPolicySnapshot`.

### Что меняется

- Добавить `SamplingPolicyConfig`.
- Добавить `SamplingPolicyConfigValidator`.
- Добавить `SamplingPolicySnapshotFactory`.
- В `otel-extension` перейти с прямого вызова `SamplingPolicySnapshot.fromConfiguration(...)` на factory.
- `SamplingPolicySnapshot.fromConfiguration(...)` оставить как временный compatibility facade.


### Что должно получиться

- `config` становится единственной точкой сборки snapshot из входных параметров.
- Нормализация и фильтрация живут не в snapshot, а в factory/validator.
- `SamplerState` перестаёт дублировать часть normalization logic.


### Риск

Средний. Тут уже можно случайно поменять семантику validation или normalization.

### Required tests

- Factory equivalence tests: старый `fromConfiguration` и новый factory должны давать одинаковый snapshot.
- Validation tests на границы, пустые/невалидные записи, порядок route ratios.


### Rollback

- Вернуть `SamplerState` к старому пути.
- Оставить `fromConfiguration` как единственный рабочий путь.


### Definition of Done

- `config` слой существует.
- otel-extension использует factory.
- Поведение snapshot идентично старому.

***

## Phase 2 — Introduce policy package

Цель: убрать rule-алгоритмы из flat-package и собрать их в единый policy layer.

### Что меняется

- Создать пакет `policy`.
- Перенести туда:
    - `SamplingPolicyRule`
    - все 7 rule classes
    - `SamplingPolicyRuleNames`
    - `TraceIdRatioDecision`
- Обновить imports внутри core.
- Обновить rule tests под новый пакет.


### Почему это правильно

- Rules и алгоритм вероятностного выбора — одна смысловая зона.
- `ratio` как отдельный пакет не нужен.
- `TraceIdRatioDecision` должен жить рядом с правилами, чтобы не ломать package-private доступ.


### Риск

Средний–высокий, потому что меняются FQN и package-private границы.

### Required tests

- `TraceIdRatioDecisionTest`.
- `RouteRatioPolicyRuleTest` с parity checks.
- `DefaultRatioPolicyRuleTest` с fractional cases.
- engine test на порядок rules после переноса.


### Rollback

- Вернуть `TraceIdRatioDecision` и rule classes в root-package.
- Откатить imports, package statements и тесты.


### Definition of Done

- Все rule-классы и ratio-logic в `policy`.
- Нет отдельного `ratio` пакета.
- Rule tests зелёные.

***

## Phase 3 — Introduce model package

Цель: выделить immutable domain model в отдельный пакет.

### Что меняется

- Создать `model`.
- Перенести туда:
    - `SamplingPolicyRequest`
    - `SamplingPolicyDecision`
    - `SamplingPolicyDecisionType`
    - `SamplingPolicyReason`
    - `ParentContextState`
    - `SamplingPolicySnapshot`
    - `RouteRatioPrefix`
- Обновить imports в `policy`, `engine`, `config`, otel-extension.


### Риск

Высокий, потому что это внешний API surface.

### Required tests

- Compile-time test для импорта нового API в otel-extension.
- Snapshot immutability tests.
- Decision constructor validation tests.
- Rule and engine tests после переноса.


### Rollback

- Вернуть model types в root-package.
- Оставить compatibility facades, если они уже введены.


### Definition of Done

- Все domain model types в `model`.
- Core runtime behavior не изменился.
- otel-extension компилируется без ручных обходов.

***

## Phase 4 — Introduce engine package

Цель: изолировать orchestration слой.

### Что меняется

- Создать `engine`.
- Перенести туда `SamplingPolicyEngine`.
- Оставить внутри только runtime orchestration.
- Production engine factory должен остаться здесь же или рядом как static factory.


### Риск

Средний. Engine сам по себе чистый, но используется внешним модулем.

### Required tests

- Engine order test.
- Never-abstains test for production chain.
- Custom engine tests if they exist.


### Rollback

- Вернуть engine в root-package.
- Обновить imports.


### Definition of Done

- Engine живёт отдельно.
- Он зависит только от `model` и `policy`.
- Нет конфиг-логики внутри engine.

***

## Phase 5 — Cleanup and deprecations

Цель: финальная зачистка и нормализация API.

### Что меняется

- `SamplingPolicySnapshot.fromConfiguration(...)` пометить deprecated.
- Старые root-package классы оставить как временные compatibility facades, если они были введены.
- Убрать дублирующую normalization logic из `SamplerState`, если она ещё осталась.
- Уточнить Javadocs и package docs.
- Зафиксировать новую архитектуру в ADR.


### Риск

Низкий, если предыдущие фазы были покрыты тестами.

### Required tests

- Full suite.
- OTel adapter parity tests.
- Concurrent runtime update tests.
- ArchUnit suite.


### Rollback

- Снять deprecation, вернуть alias path, если обнаружится неожиданный downstream use.


### Definition of Done

- Архитектура разложена по пакетам.
- `config` owns config compilation.
- `policy` owns rules + ratio algorithm.
- `model` owns immutable domain objects.
- `engine` owns orchestration.
- Flat-package больше не нужен как основная форма.

***

## Recommended ordering

Я бы делал так:

1. **Phase 0**
2. **Phase 1**
3. **Phase 2**
4. **Phase 3**
5. **Phase 4**
6. **Phase 5**

Почему именно так:

- сначала защищаем поведение;
- потом вытаскиваем config, потому что это самый очевидный источник дублирования;
- потом policy, потому что `TraceIdRatioDecision` лучше не отделять раньше, чем правила;
- потом model и engine, когда базовые алгоритмы уже стабилизированы.

***

## Что считать успехом рефакторинга

Успех — это не просто “классы лежат в новых пакетах”.
Успех — это когда:

- `model` не знает ни про config, ни про policy, ни про engine;
- `policy` не знает про Spring/OTel/JMX;
- `engine` только оркестрирует decision chain;
- `config` собирает snapshot и валидирует вход;
- OTel parity и rule order не изменились;
- дублирование normalization исчезло;
- `TraceIdRatioDecision` больше не торчит как загадочный одиночный utility.

***

## Что я бы зафиксировал в PR-плане

- В каждом PR менять только один слой.
- Не смешивать package move и semantic change в одном шаге.
- После каждого шага запускать:
    - unit tests,
    - adapter parity tests,
    - concurrent update tests,
    - ArchUnit checks.

***

Если хочешь, следующим сообщением я могу дать **готовый ArchUnit test class skeleton** под эту структуру — без production code, только правила и названия тестов.

