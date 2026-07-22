<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

> **HISTORICAL / SUPERSEDED REVIEW.** Документ фиксирует guardrails прежнего migration plan.
> Текущие package taxonomy и архитектурные границы определены в
> [финальной архитектуре](../platform-tracing-final-architecture.md).

## 11. Implementation guardrails for Cursor Composer

### 11.1. Глобальные правила

Эти правила обязательны для **любой** Cursor Composer-сессии по этому репозиторию:

- Реализовывать **только один PR за раз**; не собирать несколько PR в один change-set.
- **Никогда** не реализовывать несколько PR в одном Composer run; каждый PR — отдельная ветка и отдельный diff.
- Не удалять существующие тесты, benchmarks, e2e tests или perf scenarios; в крайнем случае — только переносить и обновлять, сохраняя поведение.[^1]
- Не коллапсировать Gradle-модули (no module collapse) в первой волне; не объединять `core`, `otel-extension`, `autoconfigure`, `webmvc`, `webflux`, `bench`, `perf-tests`.[^1]
- Не менять production behavior, если конкретный PR этого явно не допускает (PR-0–PR-4 должны быть строго behavior-preserving).[^2][^1]
- Сохранять существующие публичные стартеры `platform-tracing-spring-boot-starter-servlet` и `platform-tracing-spring-boot-starter-reactive` без ломки их зависимостей и контракта.[^1]
- Сохранять публичный `platform-tracing-api` бинарно совместимым, если архитектура/комитет явно не разрешили breaking change.[^2][^1]
- Не добавлять OTel-зависимости в пакеты чистого core (`space.br1440.platform.tracing.core.*`); core должен двигаться в сторону JDK-only, OTel — только в adapter-слоях.[^2][^1]
- Не добавлять Spring-зависимости в пакеты чистого core; Spring должен оставаться только в autoconfigure/стартер модулях.[^2][^1]
- Не вводить зависимость `spring-boot-autoconfigure` → `otel-extension` в main-источниках (никаких main `import` из `space.br1440.platform.tracing.otel.extension` в `autoconfigure`).[^1][^2]
- Не вводить зависимость `otel-extension` → Spring (никаких `org.springframework.*` в `platform-tracing-otel-extension`).[^2][^1]
- Не экспонировать Actuator MUTATION (`WriteOperation`) в production; любые новые mutation endpoints должны быть `dev-only` с явным флагом.[^1]
- Не делать scrubbing опциональным: `ScrubbingSpanProcessor` и связанные политики остаются mandatory baseline.[^1]
- Не переименовывать JMH benchmarks и не менять их `@Param` до завершения PR-0 baseline lock.[^1]
- Обязательно запускать указанные для PR тесты до и после изменений (unit, ArchUnit, e2e, JMH/Perf, где требуется).[^2][^1]

Дополнительный операционный guardrail:

- Для любого PR с JMX/Actuator/Config Server изменениями — **обязательно** запускать `RuntimeSamplingControlSmokeTest`, `TracingActuatorEndpointTest` и `RuntimeConfigApplierTest` до merge.[^2][^1]

***

### 11.2. PR-specific Composer guardrails

#### PR-0 — Baseline lock

- Не вносить **никаких** production code изменений; только фиксация baseline-метрик, конфигураций и ADR.[^2][^1]
- Не переименовывать JMH benchmark классы, пакеты, `@Param`; не менять их business-логику.[^1]
- Не менять Gradle зависимости, кроме случаев, когда это необходимо для запуска baseline (plugins/tasks/docs).
- Обязательно запускать: все JMH из `platform-tracing-bench`, `PerformanceReleaseGateTest`, `RuntimeSamplingControlSmokeTest`, e2e из `platform-tracing-e2e-tests`, M0/M5 perf (по возможности).[^2][^1]
- Документировать baseline (CPU, RSS, p99), сохранить артефакты в отдельный каталог/артефакт хранилища (например, `perf-baselines/2026-06-11`).[^1][^2]


#### PR-1 — Module taxonomy + dependency guardrails

- Не менять runtime поведение; фокус — Gradle и ArchUnit guardrails.[^2][^1]
- Не добавлять новых зависимостей между модулями; только усиливать запреты (ArchUnit, `enforcedPlatform`, dependency verification).[^1][^2]
- Явно зафиксировать, что `platform-tracing-core` не зависит от Spring/OTel SDK в новом коде; OTel остаётся в `otel-extension`.[^2][^1]
- Добавить ArchUnit тесты:
    - `ExtensionNoSpringDependencyArchTest` — `otel-extension` без Spring.[^2]
    - `AutoconfigureNoExtensionImplementationArchTest` — `autoconfigure` не импортирует `otel-extension` main-классы.[^2]
- Не изменять `settings.gradle` структуру (не удалять модули, не менять имена).


#### PR-2 — Wire schema v1 (platform-tracing-api)

- Не менять поведение JMX/MBean; добавлять только новые DTO/wire-контракты (Map/OpenMBean schema) в `platform-tracing-api`.[^1][^2]
- Не добавлять runtime зависимостей в `platform-tracing-api` кроме JDK и существующего `compileOnly OTel contextapi`.[^1]
- Не перемещать существующие классы API; только добавление новых `control`-пакетов.[^2]
- Обязательно добавить tests на константы, ключи, версию контракта; не связывать их с `otel-extension`.[^2]


#### PR-3 — Cross-CL JMX wire spike

- Не удалять существующие primitive операции `setSamplingRatio(double)` и др.; новый Map wire — **параллельный** путь под feature flag.[^1][^2]
- Не ломать `RuntimeSamplingControlSmokeTest`; он должен проходить на старом пути при выключенном флаге.[^1][^2]
- Не добавлять прямые типы из App CL в Agent CL и наоборот; всё cross-CL взаимодействие — только через JMX open types.[^1][^2]
- Обязательно протестировать: Map round-trip, invalid types, unknown keys (согласно выбранной политике).[^2][^1]


#### PR-4 — ArchUnit fitness functions

- Не менять production код, кроме добавления `@ArchTest` и вспомогательной тестовой инфраструктуры.[^2]
- Не ослаблять существующие ArchUnit правила; только усиливать (no-Spring-in-core, no-OTel-in-core, forbidden dependencies).[^1][^2]
- Обязательно запускать все ArchUnit тесты (`TracingArchRules`, `OtelSdkArchRules`, resource-model ArchUnit) до merge.[^3][^1]


#### PR-5 — Duplicate critical tests before extraction

- Не начинать extraction policy в core, пока **все** критические tests не продублированы в целевых модулях.[^1]
- Не модифицировать существующие тесты так, чтобы они перестали защищать текущее поведение; duplication ≠ re-interpretation.[^1]
- Обязательно дублировать:
    - sampling: `CompositeSamplerTest`, `SamplerRuntimeUpdateConcurrencyTest` (в test harness, пригодный для core).[^1]
    - scrubbing: `ScrubbingSecurityNegativeTest`, `MergeEngineTest`, `ExceptionEventScrubbingE2ETest` (минимально).[^1]
    - validation: `ValidationPolicyRuntimeTest`.[^1]
- Не переносить JMH в core; benchmarks остаются в `platform-tracing-bench`.[^1]


#### PR-6 — Extract sampling policy to core

- Не удалять `CompositeSampler` из `otel-extension`; сначала ввести адаптер, затем постепенно делегировать на core.[^1]
- Не менять семантику rule chain (порядок, функции, fallback); любые изменения допускаются только после JMH и e2e подтверждения.[^1]
- Не добавлять OTel types в новый core policy слой; adapter в `otel-extension` отвечает за bridging.[^2][^1]
- Обязательно:
    - Запуск duplicated tests из core + старых tests в extension.[^1]
    - Запуск sampler JMH (минимум `CompositeSamplerBenchmark`, `CompositeSamplerPolicyBranchesBenchmark`).[^1]


#### PR-7 — Extract scrubbing rule engine to core

- Не делать scrubbing optional; baseline pipeline должен всегда включать scrubbing.[^2][^1]
- Не переносить Spring или OTel типов в core rule engine; scrubbing policy должна быть чистым Java-кодом.[^2][^1]
- Не изменять `ScrubbingSecurityNegativeTest` семантику (особенно ReDoS) без согласования с Security.[^2][^1]
- Обязательно:
    - Дублированные scrubbing tests должны зелёно проходить в core и extension.[^1]
    - Запустить `ScrubbingEngineBenchmark` до/после изменений.[^2][^1]


#### PR-8 — Extract validation/enrichment policy to core

- Не изменять `ValidationMode.STRICT`/`LENIENT` семантику.[^2][^1]
- Не переносить MDC/логические зависимости в core; enrichment policy остаётся независимой от конкретных logging libs.[^1]
- Не менять `CategoryContracts` публичный API.[^1]
- Обязательно:
    - Запускаать `ValidatingSpanProcessorTest`, `CategoryContractsTest`, `EnrichingSpanProcessorTest` и дублированные core tests.[^1]


#### PR-9 — Thin OTel adapters

- Не переносить политику обратно в `otel-extension`; PR-9 только упрощает adapters поверх core.[^2][^1]
- Не менять список SpanProcessor'ов/Exporter'ов без согласованного perf evidence.[^1]
- Обязательно:
    - Прогнать `CompositePipelineBenchmark`, `QueueOfferBenchmark`.[^1]
    - Убедиться, что `PlatformDropOldestExportSpanProcessor` и `SafeSpanExporter` ведут себя идентично baseline.[^1]


#### PR-10 — Desired State Config Reconciler

- Не выключать `RuntimeConfigApplier` до доказанной стабильности `TracingConfigReconciler` (default: reconciler disabled).[^2][^1]
- Не позволять reconciler применять topology поля; topology fields должны реджектиться или игнорироваться по контракту.[^2][^1]
- Не добавлять Actuator WRITE-path без dev-only guard (будет активирован в PR-11).[^1]
- Обязательно:
    - `TracingConfigReconcilerTest`, `TracingActuatorEndpointTest` (READ) зелёные.[^1]
    - `RuntimeConfigApplierTest` остаётся зелёным; double-apply исключён.[^2][^1]


#### PR-11 — Production READ-only Actuator + dev-only mutation

- Не оставлять `WriteOperation` доступной в production без явного флага `platform.tracing.actuator.mutation.enabled=true`.[^1]
- Не полагаться только на документацию; guard должен быть enforce'нут кодом (`@ConditionalOnProperty`, профили).[^2][^1]
- Обязательно:
    - E2E: prod-профиль → mutation endpoint возвращает 404/403 или отсутствует.[^1]
    - Dev-профиль → mutation endpoint работает и управляет JMX так же, как сегодня.[^1]


#### PR-12 — Tiered pipeline defaults + perf validation

- Не менять default pipeline (scrubbing/validation/enrichment) без perf evidence под E6 gate.[^2][^1]
- Не снижать PII baseline ради perf; выключение scrubbing возможно только для optional extra rules, не для baseline.[^2][^1]
- Обязательно:
    - Снова запустить M0/M5/M10 сценарии (macro perf) и все JMH для sampler/scrubbing/queue.[^2][^1]
    - Документировать любые изменения default pipeline.[^2]


#### PR-13 — Final cleanup + deferred module simplification review

- Не делать module collapse в этом PR; он только фиксирует docs/ADR/комментарии, удаляет deprecated флаги, но не меняет CL topology.[^2][^1]
- Не удалять fallback пути (старый wire, старый control) без отдельной ADR и согласования.[^2]
- Обязательно:
    - Обновить ADR/target-architecture документы для соответствия фактической реализации.[^2]
    - Оставить явные TODO/NOTE, если какие-либо временные флаги остаются включёнными.

***

## 12. Open implementation questions

### 12.1. Policy/topology classification

**Вопрос 1:** Как точно классифицировать `platform.tracing.sampling.*` поля между policy и topology?

- Почему важно: Неверная классификация может привести к runtime изменению topology (например, переключение sampler implementation), что нарушает ADR о policy vs topology.[^2][^1]
- Блокирует какие PR: PR-6 (sampling extraction), PR-10 (reconciler), PR-12 (tiered pipeline).
- Рекомендуемый владелец: Staff engineer (Platform Tracing), совместно с SRE.
- Предлагаемый default:
    - policy: ratio, route-ratios, killSwitch, qaTrace, forceHeaders;
    - topology: выбор sampler implementation (если вынесен в конфиг), queue-size/threads.

**Вопрос 2:** Какие поля `platform.tracing.scrubbing.*` остаются строго policy и никогда topology?

- Почему важно: Scrubbing — mandatory; возможность выключить core scrubbing через Config Server в runtime может стать compliance-риском.[^2][^1]
- Блокирует: PR-7, PR-10, PR-12.
- Владелец: Security + Platform Tracing.
- Default:
    - `scrubbing.enabled` — policy (но baseline всегда true, dev/debug-only может временно выключать);
    - `rules`, `mode` — policy.

**Вопрос 3:** Как классифицировать поля `validation`/`enriching` (особенно `enabled` flags)?

- Почему важно: Validation может быть optional tier, но некоторые проверки (mandatory attributes) завязаны на SLO.[^1]
- Блокирует: PR-8, PR-12.
- Владелец: Observability/семантика (semconv) owner.
- Default:
    - `validation.enabled` — policy;
    - `enriching.enabled` — policy;
    - topology — только конфиги, влияющие на pipeline structure.

**Вопрос 4:** Какие `exporter.*` поля являются topology vs policy?

- Почему важно: exporter endpoint/queue-size — явно topology; неправильно сделать их runtime-mutated.[^2][^1]
- Блокирует: PR-9/PR-12 (pipeline tuning), PR-10 (reconciler фильтры).
- Владелец: SRE + Platform.
- Default:
    - Endpoint, protocol, queue-size — topology;
    - soft toggles (например, enable metrics processor) — policy.

**Вопрос 5:** Граница между `queue.*` как topology vs policy (например, drop policy)?

- Почему важно: Drop policy (drop-oldest vs block) влияет на runtime safety; switching in prod может нарушить perf assumptions.[^1]
- Блокирует: PR-9/PR-12.
- Владелец: SRE.
- Default:
    - queue-size — topology;
    - drop-policy — policy, но изменение требует Perf review.

**Вопрос 6:** Классификация resource attributes (service.name/version/env, platform.cgroup/id) между policy/topology.

- Почему важно: Resource model ADR уже задаёт правила, но desired state layer должен их соблюдать; иначе drift и inconsistent tags.[^3][^2]
- Блокирует: PR-10 (reconciler), PR-0/PR-2/PR-3 resource-model PRs.
- Владелец: Resource Model owner.
- Default: resource attributes — topology (startup-only), кроме `policy-version` (policy).

**Вопрос 7:** Классификация propagators и span limits (max attributes/events) как topology или policy.

- Почему важно: Изменение этих параметров в runtime может ломать perf и downstream expectations.[^2][^1]
- Блокирует: PR-12.
- Владелец: Platform Tracing + Perf.
- Default: propagators и span limits — topology (только через redeploy).

***

### 12.2. JMX wire schema

**Вопрос 8:** Что делать с неизвестными ключами в Map wire: строгое отклонение или ignore-with-metric?

- Почему важно: Strict reject ломает forward compatibility; ignore-with-metric может скрыть ошибку.[^2][^1]
- Блокирует: PR-2, PR-3, PR-10.
- Владелец: Platform + SRE.
- Default: ignore-with-metric для policy keys, strict reject для topology keys.

**Вопрос 9:** Какой numeric тип использовать в Map wire: `Double` vs `Integer` vs `Long`?

- Почему важно: OpenMBean типизация и cross-CL serialization; ошибки приведут к `ClassCastException` на Agent CL.[^2][^1]
- Блокирует: PR-2, PR-3.
- Владелец: Platform + Java/Ops.
- Default: `Double` для ratio-полей; `Integer`/`Long` — только там, где сейчас уже primitive.

**Вопрос 10:** Стратегия String parsing: где разрешены string-представления чисел (например, `"0.5"` → 0.5)?

- Почему важно: Actuator может отправлять JSON с числами; но некоторые клиенты могут передавать string, что требует fallback.[^1]
- Блокирует: PR-3, PR-10.
- Владелец: Platform.
- Default: принимать числовые типы, optionally поддерживать string → number с metric об ошибке.

**Вопрос 11:** Когда использовать `CompositeData` fallback вместо Map?

- Почему важно: Если Map окажется слишком loose, CompositeData даёт строгую схему; но миграция добавляет сложность.[^2]
- Блокирует: PR-3 (spike decision).
- Владелец: Architecture committee.
- Default: начать с Map; планировать CompositeData как v2, если Map недостаточен.

**Вопрос 12:** Стратегия `contractVersion`: где хранить и как валидировать?

- Почему важно: Нужна возможность развивать wire schema без breaking изменений; version mismatch должен быть detectible.[^2]
- Блокирует: PR-2, PR-3, PR-10.
- Владелец: platform-tracing-api owner.
- Default: `contractVersion` хранится в Map; agent отвергает неизвестные major версии, допускает minor.

***

### 12.3. Actuator behavior

**Вопрос 13:** Какое поведение для disabled mutation в prod: 404 vs 403 vs 405?

- Почему важно: 404 скрывает наличие endpoint, 403/405 явнее; выбор влияет на ops runbooks и security posture.[^1]
- Блокирует: PR-11.
- Владелец: Security + SRE.
- Default: 404 (endpoint не зарегистрирован) в prod; 200-ответы только в dev/profilях.

**Вопрос 14:** Mutation endpoint должен быть **не зарегистрирован** в prod или зарегистрирован, но запрещён?

- Почему важно: Не зарегистрированный endpoint уменьшает поверхностную площадь атак, но усложняет observability; registered-but-forbidden даёт лучший DX для ops.[^1][^2]
- Блокирует: PR-11.
- Владелец: Security.
- Default: не регистрировать WriteOperation в prod (условие на bean).

**Вопрос 15:** Какой уровень security для READ endpoint (`GET /actuator/tracing`)?

- Почему важно: READ может содержать чувствительную информацию (policyVersion, внутренние параметры).[^1]
- Блокирует: PR-10/PR-11.
- Владелец: Security + SRE.
- Default: READ endpoint доступен только на management port и защищён стандартным Spring security (role-based).

**Вопрос 16:** Какой management port/экспозиция для tracing Actuator?

- Почему важно: Один и тот же порт для всех actuator endpoints или отдельный? Это влияет на firewall и ops tooling.[^2]
- Блокирует: PR-11.
- Владелец: SRE.
- Default: использовать existing management port; не вводить новый порт, но требовать restricted exposure (internal only).

***

### 12.4. Config Server / desired state

**Вопрос 17:** Нужен ли debounce для Config Server refresh events (объединение burst-изменений)?

- Почему важно: Без debounce reconciler может вызвать множество JMX updates подряд, перегрузив control plane.[^2][^1]
- Блокирует: PR-10, PR-12.
- Владелец: Platform + SRE.
- Default: минимальный debounce (например, 1–5 секунд) с configurable параметром.

**Вопрос 18:** Как вести себя, если Config Server недоступен (fallback behavior)?

- Почему важно: Без чёткой стратегии можно потерять ability to update policy или сломать startup.[^2]
- Блокирует: PR-10.
- Владелец: SRE.
- Default: использовать last-known-good config; не падать при недоступности Config Server; drift metric сигнализирует проблему.

**Вопрос 19:** Что делать при невалидном refresh (invalid policy)?

- Почему важно: Важно сохранить LKG и предотвратить partial apply; также нужно продумать observability (metrics/logs).[^1][^2]
- Блокирует: PR-10.
- Владелец: Platform + Security.
- Default: reject apply, сохранить LKG, поднять metric и log; никакой partial apply.

**Вопрос 20:** Как вести себя, если agent отсутствует (например, локальная dev-среда без javaagent)?

- Почему важно: В dev возможно Config Server + actuator, но без агентного control plane; нужна деградация без ошибок.[^1][^2]
- Блокирует: PR-10/PR-11.
- Владелец: Platform.
- Default: reconciler возвращает `agentUnavailable=true`, записывает degraded status, но не бросает exceptions в приложении.

**Вопрос 21:** Emergency/debug override TTL — сколько времени dev override может жить?

- Почему важно: Без TTL временный override может остаться навсегда, нарушая Config Server authority.[^2]
- Блокирует: PR-11/PR-12.
- Владелец: SRE.
- Default: emergency override имеет configurable TTL (например, 1–4 часа) и auto-expire.

**Вопрос 22:** Приоритет между Config Server, Helm defaults и emergency override?

- Почему важно: Нужен строгий порядок (`Helm/env` → default → `Config Server` → emergency override), иначе drift и несогласованность.[^1][^2]
- Блокирует: PR-10/PR-11.
- Владелец: Architecture committee.
- Default: Helm/env bootstrap < Config Server < emergency override (с TTL).

***

### 12.5. Performance baseline

**Вопрос 23:** Какой hardware profile считать reference для JMH baseline (CPU/cores, RAM, OS)?

- Почему важно: Разные машины дают разные baseline; без стандарта сравнение невозможно.[^2][^1]
- Блокирует: PR-0, PR-6, PR-12.
- Владелец: Perf team.
- Default: использовать уже применяемый Gentoo perf lab профиль (описан в perf docs) как baseline.[^2]

**Вопрос 24:** Какой acceptable noise threshold для JMH (±X%)?

- Почему важно: Без порога любой шум выглядит как regression; слишком высокий порог маскирует реальные деградации.[^1][^2]
- Блокирует: PR-6/PR-7/PR-8/PR-12.
- Владелец: Perf team.
- Default: ±5–10% в зависимости от теста, с отдельным порогом для hot path benchmarks.

**Вопрос 25:** Каковы точные M0/M5 environment параметры (load, data volume, traffic mix)?

- Почему важно: E6 perf gate опирается на эти сценарии; их нужно фиксировать как контракт.[^1][^2]
- Блокирует: PR-0, PR-12.
- Владелец: Perf + SRE.
- Default: использовать существующие M0/M5 сценарии из `platform-tracing-perf-tests`, фиксируя их как "недотрогаемые" до PR-12.[^1]

**Вопрос 26:** Каков E6 CPU/RSS budget и остаётся ли он прежним (3 CPU, 10 RSS)?

- Почему важно: План миграции основывается на этом бюджете; без подтверждения нельзя принимать perf-related решения.[^2]
- Блокирует: PR-12.
- Владелец: SRE.
- Default: принять текущий бюджет как обязательный gate, пока не будет изменён комитетом.

**Вопрос 27:** Где и как хранятся baseline artifacts (лог JMH, perf результаты)?

- Почему важно: Необходим reproducible evidence; локальные машины разработчиков не подходят.[^2][^1]
- Блокирует: PR-0, PR-6, PR-12.
- Владелец: Platform + SRE.
- Default: централизованное хранилище (например, артефакты CI, отдельный S3/Arifactory bucket).

***

### 12.6. Public API compatibility

**Вопрос 28:** Какой уровень совместимости для `platform-tracing-api` с текущим `compileOnly OTel contextapi`?

- Почему важно: Переход к JDK-only API может быть breaking для downstream consumers.[^1][^2]
- Блокирует: PR-2, любые изменения API.
- Владелец: Platform + Developer Experience.
- Default: сохранить `compileOnly OTel` как есть в первой волне; breaking изменения — только после отдельного ADR.

**Вопрос 29:** Должен ли `platform-tracing-core` продолжать содержать facade implementation в первой волне?

- Почему важно: Полное разделение facade и core может быть слишком агрессивным для первой волны.[^2][^1]
- Блокирует: PR-6–PR-9.
- Владелец: Architecture committee.
- Default: facade остаётся в core в первой волне, с постепенным выделением чистого policy.

**Вопрос 30:** Где должна жить будущая имплементация facade (core vs отдельный adapter module) в долгосрочной перспективе?

- Почему важно: Влияет на public/internal разделение модулей и DX.[^1][^2]
- Блокирует: PR-13 (cleanup review).
- Владелец: Architecture committee.
- Default: core как policy + facade adapter, но без изменений в первой волне.

**Вопрос 31:** Какова binary compatibility policy: semantic versioning, backward compatibility, депрекейшн?

- Почему важно: Нельзя ломать binary API без согласования; нужен официальный policy.[^2][^1]
- Блокирует: PR-2, PR-9, PR-13.
- Владелец: Platform + DX.
- Default: соблюдение backward binary compatibility для `platform-tracing-api` (additive-only) в первой волне.

***

## 13. Final recommendation

### 13.1. Можно ли начинать миграцию?

Да, миграцию можно начинать **при выполнении условий**:

- PR-0 baseline lock выполнен: JMH, perf, e2e, ArchUnit, resource-model tests задокументированы и зафиксированы.[^1][^2]
- Архитектура Clean Core Hybrid принята в статус `Accepted` (что уже отражено в ADR).[^2]
- Основные открытые вопросы (минимальный набор из раздела 12 — policy/topology, JMX unknown key policy, Actuator prod guard) имеют согласованные defaults.


### 13.2. Первый implementation PR

Рекомендуемый первый PR:

> **PR-0 — Baseline lock: behavior, performance, dependency snapshot**

Почему именно PR-0:

- Без baseline нельзя доказать, что последующие extraction/optimization PR не изменили поведение или perf (E6 gate).[^1][^2]
- PR-0 не меняет production поведение; минимальный риск, можно выполнить сразу.[^2][^1]
- PR-0 фиксирует dependency graph, resource model и Control Plane baseline, что упрощает дальнейший review.[^1][^2]


### 13.3. PRs, которые можно выполнять параллельно (консервативно)

При строгом соблюдении guardrails возможны следующие ограниченно параллельные PR:

- PR-0 и PR-1: baseline lock и taxonomy/ArchUnit guardrails могут идти почти параллельно, если PR-1 не меняет deps/behavior, а только добавляет проверки.[^2][^1]
- PR-2 (wire schema v1 в `api`) и PR-4 (ArchUnit fitness functions): оба добавляют схемы и тесты, не меняя runtime; допускается параллельная работа при строгом CI gate.[^2]

Любая параллельность должна быть синхронизирована через rebase/merge — никакого "склеивания" PR.

### 13.4. PRs, которые **нельзя** выполнять параллельно

Эти цепочки должны быть строго последовательны:

- **PR-5 → PR-6/PR-7/PR-8**
    - PR-5 (duplicate critical tests) должен завершиться до начала extraction (sampling/scrubbing/validation), иначе потеря тестового покрытия.[^1][^2]
- **PR-6/PR-7 → PR-9**
    - PR-9 (thin OTel adapters) зависит от того, что политика уже вынесена в core; нельзя упрощать adapters до завершения extraction.[^1][^2]
- **PR-10 → полный desired-state rollout**
    - `TracingConfigReconciler` должен быть внедрён и проверен до включения по умолчанию; rollout (включение по умолчанию) — отдельный шаг, завязанный на PR-12.[^2][^1]
- **PR-11 → production rollout**
    - Продакшн rollout невозможен до того, как Actuator MUTATION будет защищён dev-only guard (PR-11).[^1][^2]
- **PR-12 → после extraction и baseline**
    - Tiered pipeline defaults и perf validation возможны только после завершения extraction (PR-6/7/8/9) и наличия стабильного baseline из PR-0.[^2][^1]


### 13.5. Review ownership

Высокоуровневая разбивка:

- **Staff/principal review (архитектура/платформа)**
    - PR-1 (taxonomy/guardrails), PR-2 (wire schema), PR-3 (JMX spike), PR-4 (ArchUnit), PR-6/7/8 (extractions), PR-10 (reconciler), PR-13 (cleanup).[^2]
- **SRE review**
    - PR-0 (baseline), PR-3 (JMX wire), PR-9 (export pipeline adapters), PR-10 (desired state), PR-11 (Actuator guards), PR-12 (perf gates).[^1][^2]
- **Security review**
    - PR-5/7 (scrubbing and security tests duplication), PR-11 (Actuator MUTATION policies), любые изменения scrubbing/PII behavior.[^1][^2]
- **Perf review**
    - PR-0, PR-6/7/8 (sampling/scrubbing/validation perf), PR-9 (pipeline perf), PR-12 (tiered defaults + E6 gate).[^2][^1]
- **Developer experience (DX) review**
    - PR-1 (dependency graph, starters), PR-2/PR-9 (public API), PR-11 (operator workflows через Actuator), PR-13 (финальные docs/ADR).[^1][^2]

***

## 14. Adversarial self-review

### Issue 1: PR-6/7/8 слишком крупные (extraction)

- **Issue:** PR-6 (sampling), PR-7 (scrubbing), PR-8 (validation/enrichment) охватывают большие подсистемы и могут быть слишком объёмными.[^2][^1]
- **Почему важно:** Большие PR сложно ревьюить; возрастает риск пропустить изменение поведения.[^1]
- **Affected PR:** PR-6, PR-7, PR-8.
- **Correction:** Разбить каждый на минимум два PR:
    - sampling: core policy extraction vs adapter wiring;
    - scrubbing: rule model vs loader/engine;
    - validation/enrichment: policy vs adapter.


### Issue 2: Недостаточно ранняя дубликация tests

- **Issue:** В планах PR-5 может недооцениваться объём тестов, которые надо продублировать до extraction (особенно e2e).[^1]
- **Почему важно:** Без duplication e2e тестов для новых core policy слоёв сложно заметить регрессии.[^1]
- **Affected PR:** PR-5, PR-6/7/8.
- **Correction:** Добавить отдельный PR `PR-5b` для e2e duplication (например, core-based harness в `platform-tracing-test`).


### Issue 3: Benchmarks запускаются слишком поздно

- **Issue:** План может подразумевать запуск JMH только на PR-6/7/8/12; но любое изменение hot path должно сопровождаться JMH ранее.[^2][^1]
- **Почему важно:** Hot path изменения без JMH могут ухудшить уже failing M5.[^2]
- **Affected PR:** PR-6, PR-7, PR-8, PR-9.
- **Correction:** Требовать запуск sampler/scrubbing JMH уже в PR-5 (duplication), а не только в extraction PR.


### Issue 4: WebMVC/WebFlux isolation риски недооценены

- **Issue:** Стек-изоляция полагается только на существующие tests; нет явных новых guardrails для изменений, связанных с core/adapters.[^1]
- **Почему важно:** Любые изменения в autoconfigure могут привести к accidental cross-stack wiring.[^1]
- **Affected PR:** PR-1, PR-9, PR-12.
- **Correction:** Добавить отдельный PR для WebStack isolation ArchUnit/CI усиления до любых adapter изменений.


### Issue 5: Starter DX риски

- **Issue:** PR-1/PR-9 могут неявно изменить transitive dependency граф стартеров.[^1]
- **Почему важно:** Starter consumers зависят от стабильной BOM/starter; ломка DX приведёт к массовым проблемам.[^1]
- **Affected PR:** PR-1, PR-9, PR-13.
- **Correction:** Ввести отдельные smoke-проекты (sample apps) для обоих стартеров и запускать их в CI.


### Issue 6: OTel/Spring leakage в core

- **Issue:** Архитектура полагается на ArchUnit, но конкретные extraction PR могут временно добавить OTel/Spring imports в core.[^2][^1]
- **Почему важно:** Нарушение Clean Core делает архитектуру менее устойчивой и усложняет тестирование.[^2]
- **Affected PR:** PR-6, PR-7, PR-8.
- **Correction:** Разбить extraction на два PR: один — перенос логики, второй — детач OTel/Spring; добавить более строгие ArchUnit rules до extraction.


### Issue 7: Actuator MUTATION prod exposure

- **Issue:** PR-10 может добавить новые пути к Actuator до того, как PR-11 введёт guard, что создаёт временное окно.[^1]
- **Почему важно:** В это окно mutation endpoint может быть доступен в prod.[^2][^1]
- **Affected PR:** PR-10, PR-11.
- **Correction:** Перенести минимальный guard (например, require dev profile) прямо в PR-10 и усилить в PR-11.


### Issue 8: JMX runtime update behavior риски

- **Issue:** PR-3 (Map wire spike) может слишком сильно изменять JMX behavior без достаточного fallback.[^2][^1]
- **Почему важно:** Control plane — критическая часть; нельзя рисковать runtime tuning.[^1]
- **Affected PR:** PR-3.
- **Correction:** Разделить PR-3 на schema-only spike и behavior change PR; оставить feature flag выключенным до E2E evidence.


### Issue 9: Config Server / RuntimeConfigApplier migration risks

- **Issue:** PR-10 может усложнить path `Config Server → RefreshScope → RuntimeConfigApplier` без достаточной изоляции от reconciler.[^2][^1]
- **Почему важно:** Двойные apply/неполные apply возможны.[^1]
- **Affected PR:** PR-10.
- **Correction:** Разделить PR-10 на `PR-10a` (types + skeleton) и `PR-10b` (hooking into events), с отдельным focus на double-apply prevention.


### Issue 10: План слишком оптимистичен в части параллелизма

- **Issue:** Допустимый параллелизм (PR-0/1, PR-2/4) может быть слишком оптимистичен для реального CI/merge потока.[^2]
- **Почему важно:** Merge конфликт и order-dependent эффекты повышают риск.[^2]
- **Affected PR:** PR-0, PR-1, PR-2, PR-4.
- **Correction:** Ограничить параллельность: выполнять PR-1 только после PR-0, PR-4 — только после PR-2.

***

## 15. Final corrected PR sequence

С учётом adversarial self-review предлагается следующая скорректированная последовательность (с разбиением крупных PR):

1. **PR-0 — Baseline lock**
Зафиксировать текущие behavior/perf/dependency baseline; собрать JMH и perf артефакты, обновить ADR/документы.[^2][^1]
2. **PR-1 — Module taxonomy + dependency guardrails**
Зафиксировать dependency направления, добавить ArchUnit тесты для CL и модульных границ (включая starter rules).[^1][^2]
3. **PR-2 — Wire schema v1 in platform-tracing-api**
Добавить Map/OpenMBean wire schema и constants в `platform-tracing-api` без изменения поведения.[^2]
4. **PR-3 — ArchUnit fitness functions**
Усилить ArchUnit (no-OTel-in-core, no-Spring-in-core, forbidden deps) до extraction; CI gate.[^1][^2]
5. **PR-4 — JMX wire spike (schema-only)**
Добавить `updatePolicy(Map)` сигнатуру и tests без включения нового пути по умолчанию; feature flag выключен.[^2][^1]
6. **PR-5 — Duplicate critical tests (unit)**
Дублировать ключевые unit tests (sampling, scrubbing, validation) в core-friendly harness, не меняя behavior.[^1]
7. **PR-5b — Duplicate critical tests (e2e)**
Добавить e2e/core-based harness для sampling/scrubbing/validation, не меняя существующие e2e tests.[^2][^1]
8. **PR-6a — Extract sampling policy to core (logic)**
Вынести pure sampling policy в core, оставить adapters в `otel-extension`; сохранить behavior.[^1]
9. **PR-6b — Adapter wiring and perf verification**
Перевести `CompositeSampler` в adapter поверх core; прогнать JMH и e2e; убедиться в отсутствии behavior-regression.[^1]
10. **PR-7a — Extract scrubbing rule engine to core (logic)**
Вынести scrubbing engine/правила в core, сохранив mandatory nature.[^2][^1]
11. **PR-7b — Scrubbing adapter wiring + perf**
Перевести `ScrubbingSpanProcessor` на core; запустить `ScrubbingEngineBenchmark`, e2e security tests.[^1]
12. **PR-8a — Extract validation/enrichment policy to core (logic)**
Вынести validation/enrichment policy в core (без OTel/Spring), сохранить public API.[^1]
13. **PR-8b — Validation/enrichment adapters + perf**
Перевести соответствующие processors; прогнать tests и perf.[^1]
14. **PR-9 — Thin OTel adapters**
Упростить `otel-extension` до тонкого adapters поверх core policy; не менять pipeline topology.[^2][^1]
15. **PR-10a — Desired State types + skeleton (reconciler disabled)**
Ввести `TracingDesiredState`, `TracingConfigReconciler` skeleton с disabled по умолчанию; добавить tests.[^2][^1]
16. **PR-10b — Reconciler integration (read-only diagnostics)**
Подключить reconciler к RefreshScope/Config Server, использовать только для diagnostics; `RuntimeConfigApplier` остаётся активным.[^2][^1]
17. **PR-11 — Actuator mutation guards**
Ввести prod/dev guard для Actuator mutation (write endpoint disabled в prod, dev-only), плюс E2E tests.[^2][^1]
18. **PR-12 — JMX Map wire enablement**
Включить Map-based JMX wire для всех операций под контролем feature flag; сохранить backwards compatibility.[^1][^2]
19. **PR-13 — Tiered pipeline defaults + perf validation**
Настроить tiered defaults (optional validation/enrichment), подтвердить E6 perf; не трогать scrubbing baseline.[^2][^1]
20. **PR-14 — Final cleanup + documentation**
Синхронизировать ADR, удалить deprecated пути/флаги (где безопасно), обновить module docs; без module collapse.[^1][^2]
```text
Pass 3 completed.

Generated sections:
- 11. Implementation guardrails for Cursor Composer
- 12. Open implementation questions
- 13. Final recommendation
- 14. Adversarial self-review
- 15. Final corrected PR sequence
```

<div align="center">⁂</div>

[^1]: platform-tracing-current-codebase-inventory.md

[^2]: platform-tracing-architecture-options.md

[^3]: faza_9_resource_model_260408ef.plan.md

