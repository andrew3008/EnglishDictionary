<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## Independent Architecture Validation Report

### 1. Executive Summary

Вердикт: **подтвердить с изменениями**.

Структура `model / rule / engine / config / ratio` в целом в духе современных Java‑платформенных библиотек, но:

- `model`, `rule`, `engine` выглядят удачно и хорошо соотносятся с практиками OpenTelemetry, Micrometer, Resilience4j и др.[^1][^2][^3][^4]
- `config` — нормальное и широко используемое имя для конфигурационных аспектов.[^5][^6][^7][^2]
- `ratio` как отдельный подпакет выглядит слишком узким и алгоритмо-ориентированным; для маленького ядра он увеличивает когнитивную нагрузку и не даёт заметной архитектурной выгоды.[^4]

Рекомендация:

- Зафиксировать `model / rule / engine / config` как целевой каркас.
- Логику `TraceIdRatioDecision` либо оставить внутри `rule`/`engine`, либо переименовать подпакет в более понятное `probability` или вообще отказаться от отдельного подпакета для алгоритма.[^4]
- Пересмотреть отказ от `internal`: для пометки non‑SPI классов это во многих OSS‑библиотеках стандартный и полезный паттерн.[^3][^8]
- Пересмотреть отказ от `compiler`: использовать его осторожно (например, `config.compiler`) там, где реально есть “компиляция” конфигурации в snapshot, а не просто “настройка”.[^9][^4]


### 2. Baseline Understanding

По инвентарю:

- Пакет `space.br1440.platform.tracing.core.sampling` — **pure‑Java, OTel‑free policy engine**, без зависимостей от Spring/JMX.[^4]
- Runtime hot path: `CompositeSampler.shouldSample()` → `SamplingPolicyOtelAdapter.toRequest()` → `SamplingPolicyEngine.evaluate(request, snapshot)` → `SamplingPolicyOtelAdapter.toSamplingResult(decision)`.[^4]
- Доменные типы: immutable `SamplingPolicyRequest`, `SamplingPolicyDecision`, `SamplingPolicySnapshot`, `RouteRatioPrefix`, enum’ы.[^4]
- Engine: `SamplingPolicyEngine` + 7 stateless rule‑классов в фиксированном порядке; `DefaultRatioPolicyRule` никогда не абстейнится → в production engine не возвращает `abstain()`.[^4]
- Конфигурация входит через otel‑extension (`SamplerPolicyUpdate`, `SamplerState`), которые вызывают `SamplingPolicySnapshot.fromConfiguration(...)`.[^4]
- Pain points:
    - смешение model / rule / engine / config‑normalization / ratio‑algorithm в одном плоском пакете;
    - дублированная нормализация в `SamplerState` и `SamplingPolicySnapshot`;
    - split validation ownership (строгая в `SamplerPolicyUpdate`, мягкая/тихая в snapshot);
    - public rule classes как потенциально случайный SPI;
    - opaque `TraceIdRatioDecision` без unit‑тестов;
    - hard‑coded production rule order.[^4]

Constraints:

- Нельзя ломать: rule order, semantics `TraceIdRatioDecision`, OTel/Spring/JMX‑free core, существующие silent‑skip/validation семантики без явного compatibility плана.[^4]


### 3. Target Architecture Under Review

#### Общий вид: `model / rule / engine / config / ratio`

**model**

- Понятность: очень хорошая — стандартное имя для доменных/value‑объектов в Java и DDD.[^10][^11]
- Ответственность: доменная модель политики — snapshot, request, decision, reason, enums.[^4]
- Риск смешения обязанностей: умеренный — главное не класть сюда compilation‑/validation‑логику; она должна жить в `config`/`engine`.[^4]
- API impact: высокий — именно отсюда идут большинство public типов; это нормально для platform library.
- Testability: хорошая — чистые immutable модели, легко тестировать в отрыве от инфраструктуры.[^4]
- Compatibility risk: низкий при аккуратных эволюциях; имена типа `SamplingPolicySnapshot` уже являются внешним контрактом.[^4]
- Cognitive load: низкий, имя `model` разработчики читают “на автомате”.

**rule**

- Понятность: хорошая — отражает текущее понятие `SamplingPolicyRule` и 7 production rules.[^4]
- Ответственность: доменные policy‑правила, реализующие chain‑of‑responsibility; сюда логично поместить все rule‑классы и interface.[^4]
- Риск смешения обязанностей: низкий, если туда не класть engine и config; но есть риск, если туда утечёт алгоритмическая утилита (`TraceIdRatioDecision`) и метаданные (`SamplingPolicyRuleNames`).[^4]
- API impact: средний — сейчас rules public, но это спорный SPI; этот подпакет помогает явно отделить потенциальный SPI от остального.
- Testability: отличная — one‑class‑per‑rule легко тестировать отдельно.[^4]
- Compatibility risk: средний — любые изменения видимости/контрактов rules могут затронуть внешних пользователей, если они есть.
- Cognitive load: низкий — `rule` лучше, чем generic `policy`, потому что отражает реализацию конкретных production rules.

**engine**

- Понятность: высокая — отражает runtime orchestration (`SamplingPolicyEngine`).[^4]
- Ответственность: цепочка правил, их порядок, абстейн‑семантика, интеграция с snapshot/request.[^4]
- Риск смешения: невысокий, если здесь не появятся config‑builderы; engine — чистый runtime.
- API impact: высокий, но понятный — `SamplingPolicyEngine` уже внешняя точка.[^4]
- Testability: хорошая — engine уже тестируется через characterization tests.[^4]
- Compatibility risk: высокий — изменение engine подпакета/контракта может затронуть otel‑extension.
- Cognitive load: низкий — разработчик сразу понимает роль.

**config**

- Понятность: очень хорошая — Micrometer, Spring, AWS SDK, Elastic Java client широко используют `config` как подпакет для конфигурационных аспектов: `instrument.config`, `client.config`, etc.[^2][^12][^9]
- Ответственность: конфигурационная модель и compilation/validation helpers — но важно не смешивать runtime policy и binding из Spring/JMX: эти останутся в других модулях.[^13][^4]
- Риск смешения: умеренный — есть соблазн “подгрузить” сюда Spring‑binding или JMX‑wire‑типы; их лучше оставить в autoconfigure/otel‑extension.[^13][^4]
- API impact: можно держать низким, если `config` в core даёт только доменно‑чистые типы (например, `SamplingPolicyConfig`), а Spring‑специфика остаётся снаружи.[^5][^4]
- Testability: высокая — компилятор и валидатор конфигов тестируются отдельно от engine.
- Compatibility risk: средний — изменение конфиг‑контрактов затронет otel‑extension и, возможно, Spring‑автоконфиг.
- Cognitive load: низкий — `config` привычен любому Spring/Micrometer/Resilience4j‑разработчику.[^2][^3]

**ratio**

- Понятность: средняя — имя отражает traceId‑ratio‑алгоритм, но слишком “low‑level” и не очевидно, что это часть sampling policy, а не математика/utility.[^4]
- Ответственность: только алгоритм `TraceIdRatioDecision` и, возможно, вспомогательные probability‑helpers.[^4]
- Риск смешения: высоковат — можно непреднамеренно начать класть сюда всё, что “про вероятности”, теряя связь с правилами/engine; или наоборот держать здесь единственный класс, создавая избыточный подпакет.
- API impact: низкий — класс уже package‑private; подпакет ещё сильнее подчёркивает внутренность, но добавляет уровень иерархии.
- Testability: отличная — отдельный подпакет/класс для алгоритма удобно тестировать, но это уже устраивается и в `rule`/`engine`.
- Compatibility risk: низкий — пока класс internal, перенос между подпакетами не ломает внешнюю API.
- Cognitive load: выше ожидаемого — разработчику нужно помнить, что sampling ratio‑algorithm живёт отдельно; для маленькой policy‑core это лишние координаты.

**Вывод по целевой структуре**
Каркас `model / rule / engine / config` вполне соответствует индустриальной практике (OpenTelemetry — `samplers` пакет + Sampler интерфейсы; Micrometer — `instrument` + `config`; Resilience4j — `core` + pattern‑modules, Spring — `config`, `boot.autoconfigure` и т.п.).[^1][^3][^13][^2]

`ratio` как субпакет — спорный: он усиливает separation алгоритма, но увеличивает когнитивную сложность и не имеет сильного прецедента в OSS‑пакетах, где traceId‑ratio‑семантика обычно живёт внутри `samplers`/`trace` пакета, а не выделяется отдельно.[^14][^15][^1]

### 4. Alternatives Considered

Оценки по шкале 1–10 (чем выше, тем лучше).

**A. Flat package (без подпакетов)**

- Pros:
    - Максимальная простота: всё в `sampling` — легко навигировать.[^4]
    - На уровне Java 21 API и маленького ядра это реалистично; OpenTelemetry Java SDK сам держит sampling в одном `samplers` пакете.[^16][^1]
- Cons:
    - Уже наблюдается смешение обязанностей: model, rules, engine, config‑compilation, ratio‑algorithm.[^4]
    - ArchUnit‑guardrails по слоям сложнее выразить, пакет сами по себе не отражают слои.[^17][^18]
- Score:
    - clarity: 6
    - maintainability: 6
    - behavioral safety: 9 (меньше движений)
    - API stability: 8
    - testability: 7
    - runtime safety: 9
    - migration cost: 10
    - extensibility: 6
    - cognitive load: 7
    - fit for Java platform library: 7

**B. model / policy / engine / config / ratio**

- Pros:
    - `policy` подчёркивает доменную “политику”, а не “rule как класс”; ближе к DDD/спецификации OTel, где есть Sampler как политика.[^14][^1]
- Cons:
    - `policy` легко превратить в свалку: правила, snapshot, компилятор — всё “примерно про политику”.
    - Для маленького ядра разделение между `policy` и `model` будет неочевидным.
- Score:
    - clarity: 7
    - maintainability: 7
    - behavioral safety: 8
    - API stability: 7
    - testability: 8
    - runtime safety: 8
    - migration cost: 7
    - extensibility: 7
    - cognitive load: 7
    - fit for Java platform library: 7

**C. api / model / rule / engine / config / ratio**

- Pros:
    - Выделение `api` для явно поддерживаемых SPI/конTRACTов (например, `SamplingPolicyRule` как SPI, `SamplingPolicyEngine` как API).[^3][^4]
    - В духе Elasticsearch Java client, где есть “core” API и “namespaces”.[^19][^9]
- Cons:
    - Для небольшого пакета это уже почти фреймворк‑градация, повышающая когнитивную нагрузку.
    - ArchUnit‑rules можно также выражать через `model/rule/engine`, отдельно `api` не обязателен.[^18][^17]
- Score:
    - clarity: 8
    - maintainability: 8
    - behavioral safety: 8
    - API stability: 8
    - testability: 8
    - runtime safety: 8
    - migration cost: 6
    - extensibility: 8
    - cognitive load: 6
    - fit for Java platform library: 8

**D. domain / rule / runtime / config / ratio**

- Pros:
    - В духе hexagonal/clean architecture: `domain` + `runtime` (application) четко соответствует подходу портов/адаптеров.[^20][^21][^10]
    - Помогает ArchUnit‑layered tests: domain не зависит от runtime/infrastructure.[^17][^18]
- Cons:
    - Для уже выделенного `core.sampling` домен/рантайм могут быть избыточными: мы не строим полный hexagonal модуль, а только policy engine.
    - `runtime` звучит слишком широко — легко туда “прилепить” то, что ближе к конфигурации или контрол‑плэйну.
- Score:
    - clarity: 7
    - maintainability: 8
    - behavioral safety: 8
    - API stability: 7
    - testability: 8
    - runtime safety: 8
    - migration cost: 6
    - extensibility: 8
    - cognitive load: 7
    - fit for Java platform library: 8

**E. model / rule / engine / config / probability**

- Pros:
    - `probability` семантически яснее, чем `ratio`: указывает на вероятность/вероятностный алгоритм для sampling.[^4]
    - Хорошо сочетается с OTel “TraceIdRatioBased”, “ParentBased”, “AlwaysOn/Off” — всё это разные probabilistic policies.[^15][^14][^1]
- Cons:
    - Всё равно остаётся вопрос: стоит ли вообще выделять подпакет для одного алгоритма в маленьком ядре.
- Score:
    - clarity: 8
    - maintainability: 8
    - behavioral safety: 9
    - API stability: 8
    - testability: 9
    - runtime safety: 9
    - migration cost: 7
    - extensibility: 8
    - cognitive load: 8
    - fit for Java platform library: 8

**F. Текущий вариант: model / rule / engine / config / ratio**

- Pros:
    - Ясно отражает основные доменные оси: модель, правила, engine, конфиг.[^4]
    - Позволяет выразить ArchUnit‑layers и отделить config‑компиляцию/валидацию от runtime.[^17]
    - ratio‑подпакет делает traceId‑ratio алгоритм явно внутренним слоем, отделённым от домена.
- Cons:
    - `ratio` как название даёт сильный акцент на один алгоритм, что для ценой подпакета спорно.
    - Для разработчика нужна дополнительная mental‑map: sampling policy разбита на четыре подпакета, плюс отдельный алгоритм — это выше когнитивной нагрузки, чем в большинстве OSS‑примеров для сравнимых по размеру библиотек.[^1][^2][^3]
- Score:
    - clarity: 8
    - maintainability: 8
    - behavioral safety: 9
    - API stability: 8
    - testability: 9
    - runtime safety: 9
    - migration cost: 7
    - extensibility: 8
    - cognitive load: 7
    - fit for Java platform library: 8

**Вывод по альтернативам**

Лучший баланс по суммарным оценкам дают варианты **E (model/rule/engine/config/probability)** и **F (текущий)**, при этом E чуть лучше по clarity/cognitive load за счёт более понятного имени подпакета и возможности вообще отказаться от отдельного пакета при небольшом объёме логики.[^1][^4]

Вариант C (с `api`) хорош как “enterprise‑grade” структура, но, на мой взгляд, избыточен для текущего размера ядра. Вариант A (flat) прост, но мешает решить наблюдаемые pain points.

### 5. Evaluation of Rejected Names: compiler, internal

**compiler**

- Когда оправдано:
    - Когда есть понятная операция “compilation”: из гибкого/сырого конфигурационного представления (maps, arrays, Spring properties) собирается иммутабельный runtime snapshot с нормализованными структурами — буквально компиляция в internal form.[^9][^4]
    - Elasticsearch Java client называет некоторые подпакеты и классы в духе “mapper”, а не “compiler”, но по сути там делают compilation JSON DSL → transport‑запроса.[^22][^9]
- Почему может быть вредным здесь:
    - Для небольшой policy‑core имя “compiler” звучит тяжелее, чем есть: подразумевает сложный DSL/язык, а не простую нормализацию и проверку конфигурации.[^4]
    - В сочетании с уже существующей логикой в `SamplerState` это может создать ощущение “двух компиляторов”, усиливая путаницу между core и otel‑extension.[^4]
- Cognitive load:
    - Средний–высокий: разработчик будет задаваться вопросом “что именно мы компилируем?” и “почему это не config/factory/builder?”.
- Лучшие альтернативы:
    - `config` (подпакет) и внутри него класс `SamplingPolicySnapshotCompiler` (ограниченно публичный или даже package‑private) — то есть использовать слово “compiler” только для конкретного класса, не для подпакета.[^4]
    - Более лёгкие имена: `builder`, `factory`, `configurator`, если semantics больше про build, а не про deterministic compilation.[^23][^2]

**internal**

- Когда оправдано:
    - Когда нужно явно пометить “non‑SPI / implementation detail” — всё, что можно поменять без обещаний по binary/API‑стабильности.[^8][^3]
    - AWS SDK v2, Resilience4j, Micrometer и другие часто используют `internal` в имени пакетов или классов, чтобы сигнализировать: “не используйте это напрямую; оно может измениться”.[^23][^8][^3]
- Почему может быть вредным здесь:
    - Для маленького модуля с уже чётко выраженным `core.sampling` internal‑подпакет может выглядеть как “чёрный ящик”, серый угол архитектуры, а не часть осознанного дизайна.[^4]
    - При отсутствии строгой политики, что именно internal, можно туда начать складывать всё подряд.
- Cognitive load:
    - Низкий для опытных Java‑разработчиков — `internal` почти везде читается одинаково: “это можно трогать только внутри/мы не гарантируем API”.
- Лучшие альтернативы:
    - Для явно внутренних утилит типа `SamplingPolicyRuleNames` и traceId‑алгоритма использование `internal` вполне допустимо и даже полезно, если в ADR чётко зафиксировать, что это не SPI.[^4]
    - Если internal принципиально отклонён, можно использовать более семантические подпакеты (`support`, `util`, `impl`), но это слабее сигнализирует о boundary.[^11][^3]

**Вывод по именам**
Отказ от `compiler` и `internal` как **подпакетов** понятен (лишняя тяжесть для маленького ядра), но полностью отбрасывать эти термины как имена отдельных классов/слоёв — слишком жёстко. Я бы всё же рассматривал `internal` для non‑SPI классов и ограниченное использование `*Compiler` там, где действительно происходит compilation config → snapshot.

### 6. OSS Evidence

**OpenTelemetry Java**

- Sampling живёт в пакете `io.opentelemetry.sdk.trace.samplers`; внутри — интерфейс `Sampler`, реализации `AlwaysOnSampler`, `AlwaysOffSampler`, `TraceIdRatioBasedSampler`, `ParentBasedSampler` и builder’ы.[^16][^1]
- Здесь нет decomposition на `model / rule / engine / ratio`, всё в одном thematic пакете `samplers`.
- Конфигурация делается через `SdkTracerProvider` и `Sampler` API, а не через отдельный `config` подпакет внутри sampling.[^24][^25]

**Spring Boot config/validation**

- Typed configuration через `@ConfigurationProperties` держится в `config`/`configuration` подпакетах; валидация — через Bean Validation (`@Validated`, JSR‑380).[^6][^7][^5][^13]
- Здесь видно, что `config` — устоявшееся имя для конфигурационных аспектов; “compiler” используется редко (чаще говорят “binder”, “mapper”).

**Micrometer**

- Основной API в `io.micrometer.core.instrument`, конфигурационные аспекты — в `io.micrometer.core.instrument.config` (например, `NamingConvention`).[^26][^2]
- Отдельных подпакетов для probability/ratio нет; алгоритмы (например, distribution statistics) живут рядом с instrument‑моделью.

**Resilience4j**

- Модульная структура: `resilience4j-core`, `resilience4j-circuitbreaker`, `resilience4j-ratelimiter` и др.[^27][^28][^3]
- Внутри `resilience4j-core` пакеты уровня `core.registry`, `core.metrics`, `core.functions` — имена отражают роль (registry, metrics), но нет узконаправленных подпакетов вроде `probability`.
- `internal` используется в именах классов и пакетов в SDK‑подобных библиотеках, но не в публичных API‑контрактах.[^8][^3]

**Caffeine / Netty / Kafka / AWS SDK / Elastic**

- Caffeine: всё в `com.github.benmanes.caffeine.cache`; ядро компактно, без subpackages типа `model`/`engine` — caching‑домейн достаточно прост.[^29][^30][^31]
- Netty: крупный проект; пакеты ориентированы по ролям: `channel`, `handler`, `handler.codec`, `handler.codec.http` и т.п.; это аналог `model/rule/engine` но в сетевом контексте.[^32][^33][^34]
- AWS SDK v2: core клиентские настройки лежат в `software.amazon.awssdk.core.client.config`; internal‑пакеты используются для внутренних деталей (например, `core.internal`).[^12][^8]
- Elasticsearch Java client: структура по “namespaces” (feature‑группы) + `core` подложка; это ближе к `api/model/engine` разделению.[^35][^22][^19][^9]

**ArchUnit / Clean / Hexagonal**

- ArchUnit прямо рекомендует использовать subpackage‑structure для выражения слоёв (например, `order.facade` и `order.service`), а не только naming conventions.[^18][^17]
- Hexagonal примеры: root: `domain`, `application`, `infrastructure`, внутри `application` — `port.in` / `port.out`, а адаптеры в `adapter.in` / `adapter.out`.[^36][^21][^10][^20]
- Это показывает, что decomposition по роли (“model”, “engine”, “port”) — нормальная практика; но “algorithm‑специфичные” подпакеты (`ratio`) встречаются редко.

**Вывод из OSS практики**
Большинство зрелых библиотек группируют код по **доменной роли** (samplers, config, registry, cache, handler, instrument, domain/application/infrastructure), а не по конкретным алгоритмам. Выделение отдельного подпакета для одного ratio‑алгоритма видно редко. В то же время выделение `config` почти универсально, а использование `internal` для non‑SPI классов — обычный инструмент API‑дизайна.

### 7. Decision Matrix

Оценки по 1–10:


| Структура | clarity | maintainability | behavioral safety | API stability | testability | runtime safety | migration cost | extensibility | cognitive load | fit for platform |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| Flat | 6 | 6 | 9 | 8 | 7 | 9 | 10 | 6 | 7 | 7 |
| model/policy/engine/config/ratio | 7 | 7 | 8 | 7 | 8 | 8 | 7 | 7 | 7 | 7 |
| api/model/rule/engine/config/ratio | 8 | 8 | 8 | 8 | 8 | 8 | 6 | 8 | 6 | 8 |
| domain/rule/runtime/config/ratio | 7 | 8 | 8 | 7 | 8 | 8 | 6 | 8 | 7 | 8 |
| model/rule/engine/config/probability | 8 | 8 | 9 | 8 | 9 | 9 | 7 | 8 | 8 | 8 |
| model/rule/engine/config/ratio (текущий) | 8 | 8 | 9 | 8 | 9 | 9 | 7 | 8 | 7 | 8 |

### 8. Final Verdict

- **Лучшая структура:**
    - **`model / rule / engine / config`** как стабильный каркас.
    - `ratio`/`probability` — **не обязательный подпакет**; traceId‑ratio алгоритм можно оставить как internal‑класс в `rule` или `engine`, либо, если хочется явного отделения, использовать более понятное имя `probability` и держать там только действительно алгоритмические вещи.[^1][^4]
- **Допустимая fallback‑структура:**
    - Текущий `model / rule / engine / config / ratio` допустим, если команда готова принять чуть повышенную когнитивную нагрузку и зафиксировать в ADR, что `ratio` — internal‑подпакет без SPI‑обещаний.[^4]
- **Структура, которую лучше не выбирать:**
    - Полностью плоский пакет (flat) на фоне уже выявленных pain points — мешает явно разделить модель, engine и конфиг.[^4]
    - Сильно “фреймворковая” структура с `api` или `domain/runtime` для такого маленького ядра — риск over‑engineering.
- **Что обязательно зафиксировать в ADR:**
    - `model` содержит только доменные value/record/enum’ы + immutable snapshot; без config‑buildup логики.[^4]
    - `rule` содержит только policy‑rules и связанную с ними метадату, без конфиг‑компиляции.[^4]
    - `engine` — единственный orchestrator rule‑цепочки; не вкуривает Spring/OTel/JMX.[^4]
    - `config` в core — только доменно‑чистые конфиг‑модели/валидация/компиляция, без Spring binding.[^5][^4]
    - traceId‑ratio алгоритм — internal (в `rule` или отдельном подпакете), без SPI‑обещаний; любые изменения согласуются с OTel parity‑tests.[^1][^4]


### 9. ADR-ready Text (English)

> **Decision:** Sampling core package structure (`space.br1440.platform.tracing.core.sampling`)
>
> We adopt a moderately decomposed package structure for the sampling core:
>
> ```text > space.br1440.platform.tracing.core.sampling > ├── model   // domain value types: Request, Decision, Snapshot, enums > ├── rule    // sampling policy rules implementing SamplingPolicyRule > ├── engine  // SamplingPolicyEngine and rule orchestration > └── config  // domain-level configuration models and helpers (no Spring/OTel) > ```
>
> The `model` package contains immutable domain types used on the hot path, including `SamplingPolicySnapshot`, `SamplingPolicyRequest`, `SamplingPolicyDecision`, and related enums. No configuration parsing or binding logic is allowed in `model`.
>
> The `rule` package contains all implementations of `SamplingPolicyRule` and associated metadata such as rule names. Rules remain stateless and are evaluated only via `SamplingPolicyEngine`.
>
> The `engine` package contains `SamplingPolicyEngine` and any orchestration helpers responsible for evaluating rules in the normative order and handling abstain semantics. The engine is pure Java and must not depend on Spring, OpenTelemetry, or JMX.
>
> The `config` package contains domain-level configuration objects and validation/normalization helpers used to compile external configuration (provided by the platform / otel-extension layer) into `SamplingPolicySnapshot`. Spring Boot binding, JMX wire formats, and environment variables remain outside the core sampling package.
>
> The trace-id ratio algorithm (`TraceIdRatioDecision`) is treated as an internal implementation detail and is not part of the public SPI. Its location (rule vs. a dedicated probability-focused subpackage) may evolve, but any changes must preserve the existing behavioral parity with OpenTelemetry’s `TraceIdRatioBasedSampler`.
>
> This structure balances clarity, maintainability, and testability without over-engineering the small sampling core. It enables ArchUnit layered checks, keeps the core OTel/Spring/JMX-free, and provides a clear place for future configuration/validation improvements.
>
> **Constraints:**
> – Normative production rule order must not change without a dedicated ADR and characterization updates.
> – `TraceIdRatioDecision` semantics must remain parity-compatible with the existing OpenTelemetry ratio sampler.
> – No Spring, OpenTelemetry, or JMX dependencies are allowed in `space.br1440.platform.tracing.core.sampling`.
> – Changes to configuration validation (silent skip vs. fail-fast) require an explicit compatibility plan and characterization tests.

### 10. Red Flags

Изменения, которые **нельзя** делать в рамках этого рефакторинга:

- **Rule order**
    - Нельзя менять порядок 7 production rules в `SamplingPolicyEngine.productionEngine()` без отдельного ADR и полного обновления characterization‑тестов; порядок является нормативным контрактом.[^4]
- **TraceIdRatioDecision semantics**
    - Нельзя менять алгоритм `TraceIdRatioDecision` (семантика 0/1, границы, длина traceId, распределение) без строгих parity‑тестов против OpenTelemetry `TraceIdRatioBased`. Любое изменение может нарушить sampling‑гарантии.[^14][^1][^4]
- **OTel/Spring/JMX dependencies in core**
    - Нельзя переносить Spring Binding (`@ConfigurationProperties`), JMX, или OTel SDK типы в `core.sampling` — это нарушит текущий модульный boundary и ArchUnit‑правила.[^17][^4]
- **Silent skip / fail-fast без совместимости**
    - Нельзя просто заменить silent‑skip invalid route entries на fail‑fast в snapshot/config без явного compatibility плана и characterization тестов для Spring/JMX‑путей.[^7][^5][^4]
- **Public API relocation без стратегии миграции**
    - Нельзя “переезжать” публичные классы (например, `SamplingPolicySnapshot`, `SamplingPolicyEngine`, rule‑классы) между подпакетами или менять их видимость, не предложив чёткую стратегию миграции (deprecation, release notes, адаптация otel‑extension).[^3][^8][^4]

Если следовать этим ограничителям, целевая структура `model / rule / engine / config` (с осторожным отношением к отдельному `ratio`/`probability` подпакету) будет хорошим балансом между чистой архитектурой и практической Java‑платформенной библиотекой.
<span style="display:none">[^37][^38][^39][^40][^41][^42][^43][^44][^45][^46][^47][^48][^49][^50][^51][^52][^53][^54][^55][^56][^57][^58][^59][^60][^61]</span>

<div align="center">⁂</div>

[^1]: https://www.javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.39.0/io/opentelemetry/sdk/trace/samplers/package-summary.html

[^2]: https://github.com/micrometer-metrics/micrometer/blob/master/micrometer-core/src/main/java/io/micrometer/core/instrument/config/NamingConvention.java

[^3]: https://github.com/resilience4j/resilience4j

[^4]: tracing-sampling-package-inventory.md

[^5]: https://dev.to/nilan/validating-configuration-properties-in-spring-boot-application-startup-21na

[^6]: https://www.logicbig.com/tutorials/spring-framework/spring-boot/configuration-properties-validation.html

[^7]: https://reflectoring.io/validate-spring-boot-configuration-parameters-at-startup/

[^8]: https://github.com/aws/aws-sdk-java-v2/blob/master/docs/LaunchChangelog.md

[^9]: https://www.elastic.co/docs/reference/elasticsearch/clients/java/api-conventions/package-structure

[^10]: https://foojay.io/today/clean-and-modular-java-a-hexagonal-architecture-approach/

[^11]: https://medium.com/@akdevblog/hexagonal-architecture-structuring-java-applications-98455c0672cf

[^12]: https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/client/config/ClientOverrideConfiguration.html

[^13]: https://medium.com/pk-cloud-code/mastering-spring-boot-configuration-properties-f6514ba50bb5

[^14]: https://opentelemetry.io/docs/languages/erlang/sampling/

[^15]: https://opentelemetry.io/docs/languages/go/sampling/

[^16]: https://github.com/open-telemetry/opentelemetry-java

[^17]: https://www.archunit.org/userguide/html/000_Index.html

[^18]: https://github.com/TNG/ArchUnit/issues/228

[^19]: https://www.elastic.co/docs/reference/elasticsearch/clients/java/getting-started

[^20]: https://www.happycoders.eu/software-craftsmanship/hexagonal-architecture-java/

[^21]: https://github.com/rajkundalia/hexagonal-architecture-demo

[^22]: https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/8.19/connecting.html

[^23]: https://github.com/micrometer-metrics/micrometer

[^24]: https://opentelemetry.io/docs/languages/java/sdk/

[^25]: https://opentelemetry.io/docs/languages/java/configuration/

[^26]: https://javadoc.io/doc/io.micrometer/micrometer-core/latest/allpackages-index.html

[^27]: https://deepwiki.com/resilience4j/resilience4j/1.2-module-structure

[^28]: https://blog.nashtechglobal.com/introduction-to-resilience4j/

[^29]: https://www.javadoc.io/static/com.github.ben-manes.caffeine/caffeine/2.5.5/com/github/benmanes/caffeine/cache/Caffeine.html

[^30]: https://www.baeldung.com/java-caching-caffeine

[^31]: https://blog.nashtechglobal.com/caffeine-cache-a-high-performance-caching-library-for-java/

[^32]: https://netty.io/4.1/api/io/netty/handler/codec/json/package-tree.html

[^33]: https://github.com/netty/netty/blob/4.1/codec-http/src/main/java/io/netty/handler/codec/http/HttpContentCompressor.java

[^34]: https://netty.io/4.1/api/overview-tree.html

[^35]: https://artifacts.elastic.co/javadoc/co/elastic/clients/elasticsearch-java/9.2.0/co/elastic/clients/elasticsearch/ElasticsearchClient.html

[^36]: https://www.youtube.com/watch?v=6-fGsqK6WUk

[^37]: https://springboot-123.mizucoffee.com/en/blog/spring-boot-configuration-properties-validation-guide/

[^38]: https://github.com/open-telemetry/opentelemetry-java/blob/main/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/samplers/AlwaysOnSampler.java

[^39]: https://www.youtube.com/watch?v=xMORu5AP67c

[^40]: https://www.youtube.com/watch?v=dlecTOxCTHc

[^41]: https://opentelemetry.io/docs/languages/java/

[^42]: https://javadoc.io/doc/io.github.resilience4j/resilience4j-core/1.6.0/index-all.html

[^43]: https://blog.csdn.net/mouday/article/details/130986401

[^44]: https://www.javadoc.io/static/io.micrometer/micrometer-core/1.7.0/index-all.html

[^45]: https://javadoc.io/static/io.github.resilience4j/resilience4j-core/2.0.0/overview-tree.html

[^46]: https://docs.oracle.com/cd/F83331_01/java-reference/com/oracle/coherence/caffeine/CaffeineCache.html

[^47]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/caffeine/CaffeineCache.html

[^48]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html

[^49]: https://www.baeldung.com/netty

[^50]: https://docs.jboss.org/netty/3.2/api/org/jboss/netty/channel/class-use/ChannelHandler.html

[^51]: https://github.com/netty/netty/blob/4.1/codec/src/main/java/io/netty/handler/codec/string/StringEncoder.java

[^52]: https://netty.io/wiki/new-and-noteworthy-in-4.0.html

[^53]: https://github.com/netty/netty/wiki/User-guide-for-4.x

[^54]: https://thelinuxcode.com/how-to-test-your-java-architecture-with-archunit/

[^55]: https://dev.to/thrsouza/hexagonal-architecture-na-pratica-construindo-software-sustentavel-com-java-e-spring-boot-2731

[^56]: https://stackoverflow.com/questions/57490788/archunit-to-test-actual-layered-architecture

[^57]: https://www.youtube.com/watch?v=xwHposBAVKk

[^58]: https://www.youtube.com/watch?v=OtOM4Xmn8Hg

[^59]: https://github.com/getsoftware-eu/clean-architecture-hexagonal-my-approach

[^60]: https://medium.com/@emedinam/implementing-hexagonal-architecture-in-java-a-practical-guide-for-clean-domain-centric-design-37c7f8ca3e80

[^61]: https://github.com/kathesama/hexarchitecture-service

