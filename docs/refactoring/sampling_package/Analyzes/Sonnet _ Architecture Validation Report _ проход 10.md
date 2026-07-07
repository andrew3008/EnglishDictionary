<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# Выполни ещё раз последний prompt с учётом того, что решение по трейсингу ещё не вышло в продакшен. Не нужно думать о ломающих изменениях и обратной совместимости.

Сейчас необходимо вычистить кодовую базу и идти на рискованные решения, если они оправданы.
Архитекторы не примут косметический рефакторинг.

Понял: в этом режиме приоритет — **не совместимость, а чистка архитектуры и устранение источников энтропии**, даже ценой ломки API.

### Короткий вывод

Я бы **пересмотрел целевую структуру** и сделал её **более агрессивной**, чем `model / rule / engine / config / ratio`.

## Что я бы выбрал

Лучший вариант для не вышедшего в продакшен кода:

```text
space.br1440.platform.tracing.core.sampling
├── model
├── policy
├── engine
└── config
```

А `ratio` — **не выделять в отдельный пакет**.
Алгоритм `TraceIdRatioDecision` лучше держать либо в `policy`, либо в `engine` как внутреннюю реализацию. Для маленького ядра отдельный пакет под один алгоритм — это лишняя гранулярность.

## Почему так

### 1. Flat-package уже мешает

Сейчас код смешивает:

- доменную модель,
- правила,
- orchestration,
- компиляцию конфигурации,
- алгоритм вероятностного выбора.

Это уже не “мало классов”, это **смешение разных причин изменения**.

### 2. `rule` — слишком узко

Название `rule` хорошее, если вся система — цепочка правил. Но у тебя есть ещё:

- snapshot compilation,
- validation,
- normalization,
- probability logic.

Поэтому `policy` лучше, чем `rule`, для слоя, где живут:

- `SamplingPolicyRule`,
- все rule implementations,
- `TraceIdRatioDecision`,
- `SamplingPolicyRuleNames`.

То есть **policy** отражает смысл, а не только технику.

### 3. `ratio` как отдельный пакет — слабое решение

Если `ratio` содержит один класс и пару тестов, это выглядит как архитектурное дробление ради дробления.
В зрелых Java-библиотеках чаще выносят:

- полноценный домен,
- полноценный слой,
- полноценный API surface.

Но не микропакет под один алгоритм, если он не самостоятельный концепт.

### 4. `config` нужен, но только если он реально чистит код

Если оставить `config` просто как “место для factory”, это косметика.
Если же туда вынести:

- `SamplingPolicyConfig`,
- `SamplingPolicyConfigValidator`,
- `SamplingPolicySnapshotFactory`,
- сборку normalized snapshot,

тогда это уже **реальное разделение ответственности**.

***

## Моя жёсткая рекомендация

Я бы предложил такой target:

```text
sampling
├── model
├── policy
├── engine
└── config
```


### Расклад по типам

- `model`:
    - `SamplingPolicyRequest`
    - `SamplingPolicyDecision`
    - `SamplingPolicyDecisionType`
    - `SamplingPolicyReason`
    - `ParentContextState`
    - `SamplingPolicySnapshot`
    - `RouteRatioPrefix`
- `policy`:
    - `SamplingPolicyRule`
    - все 7 rules
    - `TraceIdRatioDecision`
    - `SamplingPolicyRuleNames`
- `engine`:
    - `SamplingPolicyEngine`
- `config`:
    - `SamplingPolicyConfig`
    - `SamplingPolicyConfigValidator`
    - `SamplingPolicySnapshotFactory`

***

## Что это даёт

### Плюсы

- меньше пакетов;
- меньше cognitive load;
- яснее ownership;
- проще enforce’ить границы через ArchUnit;
- `TraceIdRatioDecision` не торчит отдельным “почти пустым” пакетом;
- `policy` лучше описывает смысл, чем `rule`.


### Минусы

- `policy` становится чуть плотнее;
- `TraceIdRatioDecision` остаётся рядом с правилами, а не отдельно;
- если потом пакет сильно вырастет, может понадобиться новая декомпозиция.

***

## Что бы я не делал

- Не оставлял бы flat-package.
- Не выделял бы `ratio`.
- Не делал бы `compiler` как public package.
- Не делал бы `internal` как package, если команда уже психологически не любит это слово.
- Не тащил бы Spring/OTel/JMX внутрь core.

***

## Практический вердикт

Если цель — **реально вычистить код**, а не “улучшить структуру слегка”, то:

**лучший целевой вариант: `model / policy / engine / config`**
**а `ratio` — отклонить**.

Это более честная архитектура для небольшого, но важного Java library core: она режет по смыслу, а не по микро-утилитам.

Если хочешь, я могу следующим сообщением дать уже **точный production-grade mapping всех текущих классов в эту более жёсткую структуру** и список конкретных ArchUnit-правил для неё.

