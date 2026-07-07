# Platform Tracing Migration Plan — Pass 2
## Разделы 8–10

> **Pass:** 2 из N  
> **Статус:** Завершены разделы 8, 9, 10. Разделы 11–13 намеренно не генерируются в этом проходе.  
> **Источник правды:** `platform-tracing-current-codebase-inventory.md` (snapshot 2026-06-11)

---

## 8. Desired State Configuration Layer — стратегия миграции

### 8.1. Текущее состояние

#### `TracingProperties`

`TracingProperties` — центральный `@ConfigurationProperties` класс с префиксом `platform.tracing` в модуле `platform-tracing-spring-boot-autoconfigure`. Содержит ~700+ строк вложенных классов: `Sampling`, `Scrubbing`, `Validation`, `Enriching`, `Queue`, `Exporter`, `Sdk`, `Resource` и другие. Является единственным источником конфигурации на стороне Spring Application CL.

Текущая ответственность:
- Связывает весь блок `platform.tracing.*` из `application.yaml`, env vars, Helm charts
- Является `@ConfigurationProperties` bean — создаётся при старте
- Мутация части полей разрешена через `@RefreshScope` + `SamplingControlClient` → JMX
- Не разделяет **topology** (startup-only) и **policy** (runtime-mutable) поля явно на уровне типов

#### `TracingRefreshScopeAutoConfiguration`

Условно регистрирует `TracingProperties` bean под `@RefreshScope`, если на classpath присутствует `spring-cloud-context`. При получении события `RefreshEvent` Spring пересоздаёт `TracingProperties` bean, после чего `RuntimeConfigApplier` считывает обновлённые значения и применяет их через JMX.

#### `RuntimeConfigApplier`

Применяет diff между текущими `TracingProperties` и последним known-good состоянием agent через `SamplingControlClient`. Является **предшественником** `TracingConfigReconciler` в target архитектуре. Текущее поведение:
- Слушает `EnvironmentChangeEvent` (или вызывается из `RefreshScope` пути)
- Вызывает `SamplingControlClient.updateSamplingRatio(double)` и родственные методы
- Не возвращает structured result; ошибки логируются как WARN
- Не поддерживает LKG feedback loop на уровне Spring layer (LKG живёт в agent-side `DomainConfigHolder`)

#### `SamplingControlClient`

JMX-клиент на стороне Application CL. Не импортирует типы из `platform-tracing-otel-extension` по дизайну — cross-CL boundary пересекается через `MBeanServerConnection.invoke()`. Текущие методы возвращают `Optional<Double>` / `Optional<SamplingControlUnavailableException>`. При недоступном MBean возвращает `Optional.empty()` без exception propagation (fail-silent design). Этот паттерн необходимо сохранить при миграции.

#### JMX agent-side состояние

`PlatformTracingControl` (Agent CL) реализует `PlatformTracingControlMBean` и является единственной точкой входа для runtime мутаций состояния в agent. Хранит ссылку на `SamplerStateHolder` (который обёртывает `DomainConfigHolder<SamplerState>`). При невалидном update инкрементирует `invalidConfigCounter`; хранит last-known-good состояние через `DomainConfigHolder.replace()` семантику. Текущие payload-типы: примитивы (`double`), `String[]`, частичные `Map`-операции — не все операции переведены на `Map`-based wire.

#### `DualChannelDriftDiagnostics`

Детектирует drift между Spring-side желаемым состоянием (`TracingProperties`) и agent-side applied состоянием (agent configuration via `PlatformTracingDefaultsProvider` + JMX read-back). Является **прямым предшественником** `TracingConfigDriftStatus` в target архитектуре. Текущий механизм: периодическое сравнение с эмиссией Micrometer метрики при обнаружении расхождения.

#### Гэп: `TracingConfigReconciler` не найден

`TracingConfigReconciler` **отсутствует в текущей кодовой базе** — существует только в target architecture docs. Функцию reconciler сейчас выполняет `RuntimeConfigApplier` в паре с `DualChannelDriftDiagnostics`, но без явного desired state model и без structured apply result.

---

### 8.2. Целевое состояние

```
Config Server (runtime policy authority)
  │   platform.tracing.sampling.*, scrubbing.*, validation.*, enriching.*
  ↓
Spring Environment (TracingProperties — RefreshScope)
  ↓
TracingConfigReconciler
  │   принимает TracingDesiredState (policy fields only)
  │   реджектит topology fields
  │   валидирует policy
  ↓
SamplingControlClient
  │   private in-process JMX
  │   validated Map / OpenMBean-compatible wire
  ↓
PlatformTracingControl (Agent CL, private)
  │   validates payload
  │   applies to SamplerStateHolder / policy holders
  │   LKG via DomainConfigHolder
  ↓
Agent applied state (source of truth = agent, not Spring)
```

**Источники конфигурации по типу:**

| Тип | Источник | Изменяемость в runtime | Пример полей |
|-----|----------|----------------------|--------------|
| Runtime policy | Config Server → Spring Environment | Да (`@RefreshScope`) | `sampling.ratio`, `scrubbing.rules`, `validation.mode` |
| Startup topology | Helm / env vars / system properties | Нет (требует редеплой) | `exporter.endpoint`, `sdk.mode`, `queue.size` |
| Bootstrap defaults | `PlatformTracingDefaultsProvider` | Нет | начальные значения |
| Agent applied state | `SamplerStateHolder`, policy holders в Agent CL | Изменяется только через JMX | текущий active sampler state |

**Actuator разделение:**
- `GET /actuator/tracing` — production READ endpoint: возвращает effective state, drift status, apply results
- `POST /actuator/tracing/{property}` — **dev/debug-only**: guard через `@Profile` или `@ConditionalOnProperty(platform.tracing.actuator.mutation.enabled=false)` в production

---

### 8.3. Стратегия переиспользования существующих классов

#### `TracingProperties` — сохранить

`TracingProperties` сохраняется без структурного изменения в волне 1. Reconciler читает policy fields из неё; topology fields остаются в ней как startup-only. Разделение topology vs policy **не требует переименования полей** — достаточно того, что `TracingConfigReconciler` знает, какие поля являются policy, и реджектит попытку применить topology field через reconciler path.

#### `RuntimeConfigApplier` — сохранить, эволюционировать в precursor

`RuntimeConfigApplier` сохраняется и остаётся рабочим пока reconciler в disabled режиме. После введения `TracingConfigReconciler` (PR-10) `RuntimeConfigApplier` продолжает работать как fallback path — параллельный путь на время rollout. Его логика apply через `SamplingControlClient` переиспользуется reconciler'ом, а не заменяется.

#### `TracingRefreshScopeAutoConfiguration` — сохранить

Механизм `@RefreshScope` для `TracingProperties` сохраняется. `TracingConfigReconciler` слушает те же `EnvironmentChangeEvent` / `RefreshEvent` что и `RuntimeConfigApplier` сейчас. Никаких изменений в `TracingRefreshScopeAutoConfiguration` до PR-10.

#### `DualChannelDriftDiagnostics` — сохранить, расширить

`DualChannelDriftDiagnostics` сохраняется как drift detection mechanism и становится input для `TracingConfigDriftStatus`. В PR-10 reconciler читает drift status из него как часть `TracingConfigApplyResult`.

#### `SamplingControlClient` — сохранить без изменений интерфейса

Интерфейс `SamplingControlClient` не меняется. Reconciler делегирует ему apply операции. После PR-3 (JMX wire spike) внутренняя реализация адаптируется под Map-based wire, но вызывающий код (reconciler, actuator) остаётся неизменным.

---

### 8.4. Новые target-only классы

Все нижеперечисленные классы **предполагаются target architecture**; они не существуют в текущей кодовой базе. Вводятся только в PR-10.

#### `TracingDesiredState`

```java
// platform-tracing-spring-boot-autoconfigure
// autoconfigure.configsource package
public record TracingDesiredState(
    double samplingRatio,
    Map<String, Double> routeRatios,
    boolean killSwitchEnabled,
    boolean qaTraceEnabled,
    boolean scrubbingEnabled,
    String scrubbingMode,
    boolean validationEnabled,
    String validationMode,
    boolean enrichingEnabled,
    String sourceType  // TracingConfigSourceType
) {}
```

- Содержит **только policy fields** (не topology)
- Неизменяемый record (не JavaBean)
- Строится из `TracingProperties` reconciler'ом; topology поля в `TracingProperties` игнорируются

#### `TracingConfigSourceType`

```java
public enum TracingConfigSourceType {
    CONFIG_SERVER,      // получено через Spring Cloud Config Server
    REFRESH_SCOPE,      // получено через @RefreshScope EnvironmentChangeEvent
    ACTUATOR_DEV,       // применено через Actuator (dev-only mutation)
    BOOTSTRAP_DEFAULT   // стартовые defaults
}
```

#### `TracingConfigApplyResult`

```java
public record TracingConfigApplyResult(
    boolean applied,
    TracingConfigSourceType sourceType,
    String rejectedReason,   // null если applied=true
    boolean agentUnavailable,
    boolean topologyFieldRejected,
    Instant appliedAt
) {}
```

#### `TracingConfigDriftStatus`

```java
public record TracingConfigDriftStatus(
    boolean driftDetected,
    Map<String, Object> desiredValues,  // из TracingDesiredState
    Map<String, Object> appliedValues,  // из JMX read-back
    Instant lastCheckedAt
) {}
```

#### `TracingConfigReconciler`

Основной новый класс. Регистрируется как `@Bean` в `platform-tracing-spring-boot-autoconfigure` только при `platform.tracing.reconciler.enabled=true` (default: `false` до PR-12).

```
Ответственность:
  1. Слушать EnvironmentChangeEvent / RefreshEvent
  2. Построить TracingDesiredState из TracingProperties
  3. Реджектить topology fields (reject + log + return rejected result)
  4. Валидировать policy (samplingRatio in [0,1], non-null modes)
  5. При невалидном state — НЕ применять, вернуть TracingConfigApplyResult(applied=false)
  6. Делегировать apply в SamplingControlClient
  7. Обновить TracingConfigDriftStatus через DualChannelDriftDiagnostics
  8. Эмитировать Micrometer metric platform.tracing.config.apply.result

Не реализует:
  - Логику JMX wire (делегируется SamplingControlClient)
  - Startup bootstrap (RuntimeConfigApplier остаётся для startup path)
  - Actuator READ (TracingActuatorEndpoint читает состояние независимо)
```

---

### 8.5. Поэтапный rollout

#### Этап 1: Skeleton, default disabled (PR-10 начало)

- Добавить `TracingDesiredState`, `TracingConfigApplyResult`, `TracingConfigDriftStatus`, `TracingConfigSourceType` как пустые record/enum классы
- Добавить `TracingConfigReconciler` с `@ConditionalOnProperty("platform.tracing.reconciler.enabled")` (default `false`)
- `RuntimeConfigApplier` продолжает работать на всех инсталляциях
- Тесты: класс существует, bean не создаётся при `reconciler.enabled=false`

#### Этап 2: Параллельный путь (PR-10 основная работа)

- `TracingConfigReconciler` регистрируется рядом с `RuntimeConfigApplier` при `reconciler.enabled=true`
- Оба пути слушают `EnvironmentChangeEvent`; reconciler логирует apply result без side effects на `RuntimeConfigApplier`
- `DualChannelDriftDiagnostics` обновляется для передачи drift data в reconciler
- Тесты: оба пути работают независимо; reconciler не нарушает `RuntimeConfigApplierTest`

#### Этап 3: Read-only диагностика (PR-10 финал)

- `TracingActuatorEndpoint` расширяется: `GET /actuator/tracing` возвращает `TracingConfigDriftStatus` и последний `TracingConfigApplyResult`
- Reconciler пишет apply results в in-memory buffer (последние N результатов)
- `RuntimeConfigApplier` остаётся работающим; reconciler — опциональный диагностический слой

#### Этап 4: Production Actuator READ-only guard (PR-11)

- `TracingActuatorEndpoint.WriteOperation` (`POST`) guards через `@ConditionalOnProperty("platform.tracing.actuator.mutation.enabled")` (default `false`)
- В production deployment mutation endpoint физически недоступен
- Dev profile: `platform.tracing.actuator.mutation.enabled=true` в `application-dev.yaml`
- E2E тест: SUT без dev profile → `POST /actuator/tracing/samplingRatio` → 404 или 405

#### Этап 5: Controlled enablement (PR-12)

- Default `reconciler.enabled` меняется на `true` только после E6 evidence
- `RuntimeConfigApplier` помечается `@Deprecated` (не удаляется в волне 1)
- Config Server refresh integration проверяется M10 macro perf сценарием

---

### 8.6. Обязательные тесты

Все тесты ниже относятся к PR-10 и PR-11.

| Тест | Поведение | PR |
|------|-----------|----|
| `TracingConfigReconcilerTest#configServerRefreshUpdatesDesiredState` | Публикация `EnvironmentChangeEvent` c новым `samplingRatio=0.5` → reconciler строит корректный `TracingDesiredState` | PR-10 |
| `TracingConfigReconcilerTest#topologyFieldRejected` | Попытка применить `exporter.endpoint` через reconciler → `TracingConfigApplyResult(applied=false, topologyFieldRejected=true)` | PR-10 |
| `TracingConfigReconcilerTest#invalidPolicyPreservesLkg` | Невалидный `samplingRatio=-1` → reconciler возвращает `applied=false`, JMX не вызывается, LKG в agent не нарушен | PR-10 |
| `TracingConfigReconcilerTest#agentAbsentDegradedApplyStatus` | `SamplingControlClient` недоступен → `TracingConfigApplyResult(agentUnavailable=true, applied=false)` | PR-10 |
| `TracingConfigReconcilerTest#desiredNotEqualAppliedDriftMetric` | Reconciler apply success, но JMX read-back отличается → drift metric эмитируется | PR-10 |
| `TracingActuatorEndpointTest#readReturnsDriftStatus` | `GET /actuator/tracing` → body содержит `driftDetected`, `lastCheckedAt`, последний `applyResult` | PR-10 |
| `TracingActuatorEndpointTest#mutationDisabledInProd` | SUT без `platform.tracing.actuator.mutation.enabled=true` → `POST /actuator/tracing/samplingRatio` → 404/405 | PR-11 |
| `ActuatorMutationProdGuardE2ETest` | E2E: agent deployment без dev profile → Actuator mutation endpoint недоступен | PR-11 |
| `RuntimeConfigApplierTest#remainsFunctionalWhileReconcilerEnabled` | При включённом reconciler `RuntimeConfigApplierTest` продолжает зеленеть без изменений | PR-10 |

---

## 9. ClassLoader и JMX — стратегия миграции

### 9.1. Текущее состояние

#### Топология ClassLoader'ов

```
┌──────────────────────────────────────────────────────┐
│  JVM Process                                         │
│                                                      │
│  ┌─────────────────────────────────────────────┐     │
│  │  Agent ClassLoader (isolated)               │     │
│  │  platform-tracing-otel-extension (agent jar)│     │
│  │  platform-tracing-api (embedded copy)       │     │
│  │  OTel SDK + SPI                             │     │
│  │                                             │     │
│  │  PlatformTracingControl (MBean server)      │     │
│  │  SamplerStateHolder                         │     │
│  │  DomainConfigHolder<SamplerState>           │     │
│  └─────────────────────────────────────────────┘     │
│                           ↑ JMX MBeanServer           │
│                           │ (in-process, no network) │
│  ┌─────────────────────────────────────────────┐     │
│  │  Application ClassLoader                    │     │
│  │  Spring Boot fat JAR                        │     │
│  │  platform-tracing-api (app copy)            │     │
│  │  platform-tracing-core                      │     │
│  │  platform-tracing-spring-boot-autoconfigure │     │
│  │                                             │     │
│  │  SamplingControlClient (JMX client)         │     │
│  │  TracingProperties                          │     │
│  └─────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────┘
```

**Ключевые факты о текущем дизайне:**

- `platform-tracing-api` загружается в **обоих** ClassLoader'ах: в App CL из fat JAR и в Agent CL как embedded copy внутри `agentExtensionJar`
- Это нормально: `platform-tracing-api` является CL-neutral (JDK-only зависимости)
- `SamplingControlClient` (App CL) **не импортирует** типы из `platform-tracing-otel-extension` — cross-CL boundary пересекается через `MBeanServer.invoke()` по имени операции
- Текущие payload-типы через JMX: примитивы (`double`), `String[]`, частичные `Map<String, Object>` операции
- Raw Java DTO через CL boundary **не передаётся** — текущий дизайн специально избегает этого
- `ClassLoaderVisibilitySpikeE2ETest` и Gradle task `verifyExtensionDeps` охраняют этот инвариант

#### Текущие MBean операции (частичный список, `PlatformTracingControl`)

| Операция | Payload | Тип | Статус |
|----------|---------|-----|--------|
| `setSamplingRatio(double)` | примитив | policy | primitive wire |
| `getSamplingRatio()` | `double` | read | primitive wire |
| `getExtensionStatus()` | `Map<String, Object>` | read | Map wire |
| `reloadConfig(Map<String, Object>)` | `Map` | policy | partial Map — **MIGRATION_RISK** |
| `getInvalidConfigCounter()` | `int` | diagnostics | primitive wire |

Частичная `Map`-based реализация в `PlatformTracingControl` методах для reload операций указана в инвентаре как **Map payloads partial** — не все операции переведены на единый wire формат.

---

### 9.2. Целевое состояние

**Принципы target wire:**

1. **Private in-process JMX only** — MBean зарегистрирован в `ManagementFactory.getPlatformMBeanServer()` с именем из константы в `platform-tracing-api`; никакого remote JMX exposure в production
2. **Validated Map / OpenMBean-compatible wire** — все payload'ы через JMX передаются как `Map<String, Object>` с явной схемой; типы значений — только JMX open types (String, Integer, Double, Long, Boolean, String[])
3. **Нет raw Java DTO через CL boundary** — нет `instanceof` проверок на extension-side типы из App CL и наоборот
4. **`PlatformTracingControl` остаётся приватным agent-side адаптером** — не является частью public API; его имя MBean и операции определяются константами в `platform-tracing-api`
5. **`SamplingControlClient` остаётся app-side клиентом** — интерфейс не меняется для вызывающего кода (reconciler, actuator)
6. **`CompositeData` как fallback** — если Map-based wire окажется слишком loose (слабая типизация, нет schema enforcement), переход на `CompositeData`/`CompositeType` (OpenMBean standard) документируется как запланированный follow-up

**Target wire schema (PR-2, `platform-tracing-api`):**

```java
// Константы схемы в platform-tracing-api
public final class TracingControlWireSchema {
    // Key names
    public static final String KEY_SAMPLING_RATIO      = "samplingRatio";
    public static final String KEY_ROUTE_RATIOS        = "routeRatios";     // Map<String,Double>
    public static final String KEY_KILL_SWITCH         = "killSwitch";
    public static final String KEY_QA_TRACE            = "qaTrace";
    public static final String KEY_CONTRACT_VERSION    = "contractVersion"; // String, semantic versioning
    public static final String KEY_POLICY_VERSION      = "policyVersion";   // String, content hash/timestamp
    
    // Validation helpers (используются в обоих CL)
    public static boolean isTopologyKey(String key) { ... }
    public static boolean isPolicyKey(String key) { ... }
}
```

Схема живёт в `platform-tracing-api` (доступен в обоих CL), что позволяет и `SamplingControlClient` (App CL), и `PlatformTracingControl` (Agent CL) использовать одни и те же строковые константы без cross-CL type reference.

---

### 9.3. Шаги миграции

#### PR-2: Wire schema v1 в `platform-tracing-api`

**Объём:**
- Создать `TracingControlWireSchema` — только константы и validation helpers
- Создать `TracingWireSchemaVersion` — semantic version string (e.g., `"1.0"`)
- Добавить unit тесты: `TracingControlWireSchemaTest` — schema constants not null, topology/policy key classification

**Критичное ограничение:** PR-2 **не изменяет** поведение `PlatformTracingControl` или `SamplingControlClient`. Это только additive — новые классы в api. Обратная совместимость полная.

**Верификация:**
- `ArchUnit` правило в PR-4: `autoconfigure` не импортирует `otel-extension` типы (кроме `platform-tracing-api`)
- `TracingControlWireSchemaTest` — зелёный

#### PR-3: Cross-CL JMX wire spike

**Объём:**
- Spike: реализовать одну операцию (`setSamplingRatio`) через Map wire в параллель к существующему primitive wire
- `PlatformTracingControl` добавляет `updatePolicy(Map<String, Object>)` операцию
- `SamplingControlClient` добавляет `updatePolicy(Map<String, Object>)` метод
- Оба пути работают; новый путь прикрыт feature flag (`platform.tracing.jmx.wire.v1=false` default)
- E2E: `ClassLoaderVisibilitySpikeE2ETest` остаётся зелёным
- `RuntimeSamplingControlSmokeTest` остаётся зелёным на старом пути

**Документирует:**
- Режим `CompositeData` как альтернативу если Map слишком loose
- Поведение при неизвестном ключе в Map (log + ignore vs reject)
- Поведение при неверном типе значения (reject + `invalidConfigCounter` increment)

#### Последующие PR (после PR-6/PR-7/PR-8): Перевод существующих методов

После extraction sampling/scrubbing policy в core (PR-6/PR-7) возникает возможность унифицировать wire под Map-based формат. Существующие primitive методы (`setSamplingRatio(double)`) мигрируют внутрь `updatePolicy(Map)`:

```
PR-6 (sampling extraction): SamplerState → Map serialization helper (в core)
PR-7 (scrubbing extraction): ScrubbingSnapshot → Map serialization helper (в core)  
PR-8 (validation/enrichment): ValidationSnapshot, EnrichingSnapshot → Map helpers
```

Каждый из этих PR добавляет Map serialization в core (без OTel зависимостей) и обновляет `PlatformTracingControl` на использование deserialized policy objects из Map.

#### PR-10: Полный Map wire для всех операций

После reconciler introduction все runtime-mutable операции идут через `updatePolicy(Map)`. Primitive `setSamplingRatio(double)` помечается `@Deprecated` но не удаляется в волне 1.

---

### 9.4. Обязательные тесты

| Тест | Покрываемое поведение | PR |
|------|-----------------------|----|
| `TracingControlWireSchemaTest#validMapPayloadRoundTrip` | Map с `KEY_SAMPLING_RATIO=0.5` → `PlatformTracingControl.updatePolicy()` → `SamplerStateHolder.current().ratio == 0.5` | PR-3 |
| `PlatformTracingControlTest#invalidValueTypeRejected` | Map с `KEY_SAMPLING_RATIO="not-a-double"` → `invalidConfigCounter` инкрементируется; LKG не нарушен | PR-3 |
| `PlatformTracingControlTest#unknownKeyIgnoredPerPolicy` | Map с `"unknownKey"="value"` → не бросает exception; операция применяется (остальные ключи валидны) | PR-3 |
| `PlatformTracingControlTest#topologyFieldRejected` | Map с `"exporter.endpoint"="http://..."` → rejected, `invalidConfigCounter` инкрементируется | PR-3 |
| `SamplingControlClientTest#rawDtoFailureModeDocumented` | Попытка передать non-open-type значение через JMX → `SamplingControlUnavailableException` или `Optional.empty()`; не ClassCastException | PR-3 |
| `RuntimeSamplingControlSmokeTest` | E2E: sampling control через JMX работает после введения wire schema | PR-3, обязательно зелёный |
| `PlatformTracingControlTest` | Адаптировать существующие тесты для `updatePolicy(Map)` параллельного пути | PR-3 |
| `SamplingControlClientTest` | Адаптировать: добавить `updatePolicy(Map)` тест cases | PR-3 |
| `ClassLoaderVisibilitySpikeE2ETest` | App CL не видит Agent CL типы при работе с Map wire | PR-3, зелёный |
| `TracingControlWireSchemaTest#contractVersionPresent` | Map payload содержит `KEY_CONTRACT_VERSION`; agent проверяет версию | PR-3 |

---

## 10. Высокорискованные области миграции

Таблица упорядочена по убыванию severity: `CRITICAL` → `HIGH` → `MEDIUM`.

---

### Риск 1: Потеря sampling семантики

| Атрибут | Значение |
|---------|---------|
| **Риск** | Изменение порядка или логики rule chain `KillSwitch → ForceHeader → QaTrace → RouteRatio → DefaultRatio → HardDrop → ParentDecision` при extraction в core |
| **Почему рискованно** | Sampling — горячий путь каждого root span. Изменение порядка правил → неверный sampling rate в production → compliance / observability incident. Rule chain тестируется as-a-whole в `CompositeSamplerTest`, но не каждая перестановка |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-core` (после PR-6) |
| **Затронутые классы** | `CompositeSampler`, `KillSwitchRule`, `ForceHeaderRule`, `QaTraceRule`, `RouteRatioRule`, `DefaultRatioRule`, `HardDropRule`, `ParentDecisionRule`, `SamplerStateHolder` |
| **Существующие защиты** | `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest`, `RouteRatioTest`, `SamplerRuntimeUpdateConcurrencyTest`, `RuntimeSamplingControlSmokeTest` (e2e), `CompositeSamplerBenchmark`, `CompositeSamplerPolicyBranchesBenchmark` |
| **Отсутствующие защиты** | Нет characterization test на полный порядок правил с explicit ordering assertion; нет теста pure policy в JDK-only окружении (без OTel) до PR-5 |
| **Рекомендуемый PR** | PR-5 (дублировать тесты правил в core), PR-6 (extraction) |
| **Mitigation** | DUPLICATE_BEFORE_MOVE: все rule тесты дублировать в core test source до PR-6; добавить ordering characterization test; `CompositeSamplerBenchmark` baseline до/после |
| **Требует review** | Security review: нет; SRE review: **да** — sampling rate изменение влияет на ingestion cost |

---

### Риск 2: Потеря обязательного PII scrubbing

| Атрибут | Значение |
|---------|---------|
| **Риск** | Нарушение mandatory baseline scrubbing при extraction rule engine в core; fail-open семантика теряется или инвертируется |
| **Почему рискованно** | Scrubbing является **mandatory** — не optional tier. Если scrubbing engine бросает exception и он не перехвачен, span экспортируется с PII данными. Rule circuit breaker должен продолжать работу после extraction |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-core` (после PR-7) |
| **Затронутые классы** | `ScrubbingSpanProcessor`, `ScrubbingPolicyHolder`, `scrubbing.engine.*`, `BuiltInRules`, `RuleCircuitBreaker` |
| **Существующие защиты** | `ScrubbingSpanProcessorTest`, `ScrubbingSecurityNegativeTest`, `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExceptionEventScrubbingE2ETest`, `ScrubbingEngineBenchmark` |
| **Отсутствующие защиты** | Нет изолированного теста fail-open: engine exception → span export continues — на уровне pure core без OTel; нет теста что `ScrubbingSecurityNegativeTest` (ReDoS) проходит в core без OTel context |
| **Рекомендуемый PR** | PR-5 (обязательно дублировать `ScrubbingSecurityNegativeTest` в core), PR-7 (extraction) |
| **Mitigation** | MUST_KEEP all scrubbing tests; `ScrubbingEngineBenchmark` before/after PR-7; circuit breaker тесты в core до extraction |
| **Требует review** | Security review: **да** — PII compliance; SRE review: **да** |

---

### Риск 3: Потеря обязательных span атрибутов

| Атрибут | Значение |
|---------|---------|
| **Риск** | После extraction `ValidatingSpanProcessor` policy в core — mandatory attributes (`platform.trace.type`, `service.name`, `platform.cgroup`) перестают валидироваться или `ValidationMode.STRICT` перестаёт работать |
| **Почему рискованно** | Mandatory attributes — контрактное требование для downstream processing (routing, alerting). Silent loss не обнаруживается в unit тестах если adapter неправильно делегирует в core policy |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-core` (после PR-8) |
| **Затронутые классы** | `ValidatingSpanProcessor`, `ValidationPolicyHolder`, `CategoryContracts`, `ValidationMode` |
| **Существующие защиты** | `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest`, `CategoryContractsTest` |
| **Отсутствующие защиты** | Нет теста `ValidationMode.STRICT` vs `LENIENT` в core без OTel context |
| **Рекомендуемый PR** | PR-5 (дублировать `ValidationPolicyRuntimeTest` в core), PR-8 |
| **Mitigation** | Тест: validation enforcement результат идентичен до/после extraction; adapter тест: OTel processor делегирует в core policy корректно |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 4: Поломка export safety

| Атрибут | Значение |
|---------|---------|
| **Риск** | Изменение поведения `PlatformDropOldestExportSpanProcessor` drop-oldest семантики или `SafeSpanExporter` fail-safe при изменении зависимостей в PR-9 / PR-12 |
| **Почему рискованно** | Export overflow → silent span loss. BSP без custom processor → export storm при Collector failure |
| **Затронутые модули** | `platform-tracing-otel-extension` |
| **Затронутые классы** | `PlatformDropOldestExportSpanProcessor`, `SafeSpanExporter`, `DegradedModeController`, `CircuitBreaker` |
| **Существующие защиты** | `PlatformDropOldestExportSpanProcessorTest` (overflow, lifecycle, builder), `SafeSpanExporterTest`, `BspDropOldestSafetyAgentSmokeTest`, `QueueOfferBenchmark` |
| **Отсутствующие защиты** | Нет теста: queue size изменение через `TracingProperties` → processor rebuilds correctly (не restart artifact) |
| **Рекомендуемый PR** | PR-0 (зафиксировать baseline), нет изменений в волне 1 без явного PR |
| **Mitigation** | Не трогать `PlatformDropOldestExportSpanProcessor` в волне 1; `QueueOfferBenchmark` baseline до PR-12 |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 5: Поломка Spring property binding

| Атрибут | Значение |
|---------|---------|
| **Риск** | Silent bind failure при рефакторинге `TracingProperties` или добавлении reconciler beans — `platform.tracing.*` перестаёт биндиться без exception |
| **Почему рискованно** | Silent failure — application стартует с default конфигурацией вместо configured; sampling ratio 0.0 вместо 0.1 не обнаруживается до production incident |
| **Затронутые модули** | `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `TracingProperties`, все 13 `AutoConfiguration` классов |
| **Существующие защиты** | `TracingPropertiesBindingTest`, `TracingAutoConfigurationTest`, `SharedDefaultsAlignmentTest` |
| **Отсутствующие защиты** | Нет negative test: неизвестный `platform.tracing.unknown.key` не вызывает startup failure (или наоборот — вызывает, если так задумано) |
| **Рекомендуемый PR** | PR-0 (baseline), PR-10 (reconciler addition) |
| **Mitigation** | `TracingPropertiesBindingTest` запускать в CI на каждом PR; при добавлении reconciler bean проверять что `@ConditionalOnProperty` не нарушает существующий binding |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 6: Поломка WebMVC поведения

| Атрибут | Значение |
|---------|---------|
| **Риск** | Утечка WebFlux beans в Servlet stack или нарушение duplicate span suppression при изменении dependency graph в PR-1/PR-4 |
| **Почему рискованно** | Дублирование HTTP spans в MVC приложении. В Servlet deployment без WebFlux — `ClassNotFoundException` при lazy init |
| **Затронутые модули** | `platform-tracing-autoconfigure-webmvc`, `platform-tracing-spring-boot-starter-servlet` |
| **Затронутые классы** | `ServletTracingAutoConfiguration`, `TraceResponseHeaderServletFilter`, `PlatformServerRequestObservationConvention` |
| **Существующие защиты** | `WebStackIsolationTest`, `DuplicateSpansRegressionMatrixTest` (webmvc часть), `TraceResponseHeaderServletFilterTest` |
| **Отсутствующие защиты** | Нет автоматизированного classpath isolation test: стартер не тянет `reactor-core` |
| **Рекомендуемый PR** | PR-1 (taxonomy guardrails), CI на всех PR |
| **Mitigation** | `WebStackIsolationTest` обязателен в CI gate на каждом PR; ArchUnit rule: `webmvc` не импортирует `reactor.*` |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 7: Поломка WebFlux поведения

| Атрибут | Значение |
|---------|---------|
| **Риск** | Потеря Reactor context propagation → trace ID не пробрасывается через `publishOn`/`subscribeOn`; MDC пуст в реактивных цепочках |
| **Почему рискованно** | Silent failure: spans создаются, но trace correlation теряется в reactive стеке; обнаруживается только через distributed tracing review |
| **Затронутые модули** | `platform-tracing-autoconfigure-webflux` |
| **Затронутые классы** | `TracingReactorEagerInitConfiguration`, `BridgeOtelReactorContextPropagation`, `TracingReactorContextPropagationStartupRunner` |
| **Существующие защиты** | `TracingReactorEagerInitConfigurationTest`, `ReactorContextPropagationIntegrationTest`, `MdcPropagationWebFluxIntegrationTest` |
| **Отсутствующие защиты** | Нет теста `BridgeOtelReactorContextPropagation` не инициализируется в Servlet-only context |
| **Рекомендуемый PR** | PR-0 (baseline), CI на всех PR |
| **Mitigation** | `TracingReactorEagerInitConfigurationTest` в CI gate; ArchUnit rule: `webflux` не импортирует `servlet.*` |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 8: Поломка starter developer experience

| Атрибут | Значение |
|---------|---------|
| **Риск** | Accidental transitive dependency добавляется в стартер → consumer dependency graph ломается; BOM version conflict |
| **Почему рискованно** | Любое изменение transitive dependency стартера требует coordination с 50+ downstream consumers. Startup failure у consumers — критический инцидент |
| **Затронутые модули** | `platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive` |
| **Затронутые классы** | `build.gradle` файлы стартеров (0 Java классов) |
| **Существующие защиты** | Dependency-only модули (нет Java кода); `DuplicateHttpSpanAgentSmokeTest`, `PlatformExtensionAgentSmokeTest` (e2e) |
| **Отсутствующие защиты** | Нет automated test: стартер-servlet не содержит `reactor-core` в compile classpath |
| **Рекомендуемый PR** | PR-1 (ArchUnit classpath isolation rules для стартеров) |
| **Mitigation** | ArchUnit rule: starter dependencies проверяются на запрещённые transitive imports; BOM pin на каждый external dependency |
| **Требует review** | Security review: нет; SRE review: нет; **Platform team lead review: да** |

---

### Риск 9: Поломка JMX runtime updates

| Атрибут | Значение |
|---------|---------|
| **Риск** | Cross-CL boundary нарушается при введении Map wire (PR-3) — `ClassCastException` или `IncompatibleClassChangeError` при JMX invoke |
| **Почему рискованно** | Ops теряет возможность менять sampling ratio через Actuator/JMX в production без redeploy. M10 macro scenario поломан |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `PlatformTracingControl`, `SamplingControlClient`, `TracingActuatorEndpoint` |
| **Существующие защиты** | `PlatformTracingControlTest`, `SamplingControlClientTest`, `RuntimeSamplingControlSmokeTest` (e2e, CRITICAL) |
| **Отсутствующие защиты** | Нет теста Map round-trip: payload serialized в App CL, deserialized в Agent CL — ни один объект не пересекает CL boundary как Java type |
| **Рекомендуемый PR** | PR-2 (wire schema), PR-3 (spike — обязательно) |
| **Mitigation** | `RuntimeSamplingControlSmokeTest` зелёный — жёсткий gate для PR-3; `ClassLoaderVisibilitySpikeE2ETest` зелёный; feature flag на новом wire пути до подтверждения в E2E |
| **Требует review** | Security review: нет; SRE review: **да** — операционный control plane |

---

### Риск 10: Поломка RefreshScope / RuntimeConfigApplier

| Атрибут | Значение |
|---------|---------|
| **Риск** | При введении `TracingConfigReconciler` (PR-10) `RuntimeConfigApplier` перестаёт вызываться или вызывается дважды при одном RefreshEvent |
| **Почему рискованно** | Двойной apply → `invalidConfigCounter` инкрементируется без причины; частичный apply → drift без detection |
| **Затронутые модули** | `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `RuntimeConfigApplier`, `TracingRefreshScopeAutoConfiguration`, `TracingConfigReconciler` (новый) |
| **Существующие защиты** | `RuntimeConfigApplierTest`, `DualChannelDriftDiagnosticsTest` |
| **Отсутствующие защиты** | Нет теста: оба bean слушают одно `EnvironmentChangeEvent` → только одна JMX операция вызывается |
| **Рекомендуемый PR** | PR-10 |
| **Mitigation** | Reconciler и `RuntimeConfigApplier` должны быть mutual-exclusive или reconciler явно предотвращает double-apply; `RuntimeConfigApplierTest` должен оставаться зелёным при включённом reconciler |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 11: Случайное exposure Actuator MUTATION в production

| Атрибут | Значение |
|---------|---------|
| **Риск** | `TracingActuatorEndpoint.WriteOperation` доступна в production deployment → оператор или атакующий может изменить sampling ratio до 0.0 (drop all spans) или включить aggressive scrubbing mode |
| **Почему рискованно** | Нет prod/dev guard в текущем коде — `WriteOperation` регистрируется unconditionally при наличии actuator на classpath. Инвентарь явно отмечает: `MIGRATION_RISK — Current Actuator MUTATION exposure: YES, no prod disable guard found` |
| **Затронутые модули** | `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `TracingActuatorEndpoint`, `TracingActuatorAutoConfiguration` |
| **Существующие защиты** | `TracingActuatorEndpointTest` (но не тестирует prod guard — guard отсутствует) |
| **Отсутствующие защиты** | Нет теста: production profile → `WriteOperation` недоступна; нет E2E теста для этого |
| **Рекомендуемый PR** | PR-11 (production READ-only Actuator + dev-only mutation guard) |
| **Mitigation** | `@ConditionalOnProperty("platform.tracing.actuator.mutation.enabled", havingValue="true", matchIfMissing=false)` на `WriteOperation`; PR-11 добавляет E2E тест для этого |
| **Требует review** | Security review: **да** — mutation as attack vector; SRE review: **да** |

---

### Риск 12: Утечка OTel типов в pure core

| Атрибут | Значение |
|---------|---------|
| **Риск** | После PR-6/PR-7/PR-8 extraction в `platform-tracing-core` туда случайно попадают `import io.opentelemetry.*` — нарушение Clean Core изоляции |
| **Почему рискованно** | Core с OTel dependency невозможно тестировать без OTel SDK; future reuse в non-OTel context невозможен; ClassLoader изоляция нарушена концептуально |
| **Затронутые модули** | `platform-tracing-core` |
| **Затронутые классы** | Любые новые policy классы в core после extraction |
| **Существующие защиты** | `OtelSdkArchRulesTest` (частичный) |
| **Отсутствующие защиты** | Нет ArchUnit rule специфически для `platform-tracing-core`: «no OTel imports» — до PR-4 |
| **Рекомендуемый PR** | PR-4 (ArchUnit fitness functions) |
| **Mitigation** | PR-4: добавить `noOtelInCore` ArchUnit rule; CI gate blocks extraction PR если rule fails |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 13: Утечка Spring типов в pure core

| Атрибут | Значение |
|---------|---------|
| **Риск** | Policy классы в `platform-tracing-core` начинают импортировать `org.springframework.*` — нарушение Clean Core изоляции |
| **Почему рискованно** | Core становится Spring-dependent → не может быть протестирован без Spring context; agent-side reuse невозможен (agent не имеет Spring) |
| **Затронутые модули** | `platform-tracing-core`, `platform-tracing-otel-extension` |
| **Затронутые классы** | Любые новые policy классы |
| **Существующие защиты** | `TracingArchRulesTest` (общие правила) |
| **Отсутствующие защиты** | Нет правила `noSpringInCore`; нет правила `noSpringInExtension` |
| **Рекомендуемый PR** | PR-4 (ArchUnit fitness functions) |
| **Mitigation** | PR-4: `noSpringInCore`, `noSpringInExtension` ArchUnit rules; PR-1: `build.gradle` dependency declarations не добавляют Spring в core/extension |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 14: Потеря benchmark comparability

| Атрибут | Значение |
|---------|---------|
| **Риск** | JMH benchmark классы переименовываются или `@Param` значения изменяются до PR-0 baseline capture → baseline несравним с post-extraction результатами |
| **Почему рискованно** | Нет доказательной базы для E6 gate; performance regression незаметна; committee review не проходит |
| **Затронутые модули** | `platform-tracing-bench` |
| **Затронутые классы** | `CompositeSamplerBenchmark`, `ScrubbingEngineBenchmark`, `QueueOfferBenchmark`, все 16 JMH классов |
| **Существующие защиты** | `PerformanceReleaseGateTest` (hard budget contract) |
| **Отсутствующие защиты** | Нет CI check на: benchmark class names unchanged from baseline; нет frozen `@Param` contract test |
| **Рекомендуемый PR** | PR-0 (baseline capture) |
| **Mitigation** | Правило: JMH class names и `@Param` заморожены до PR-0 завершения; обновление deps в bench module только после extraction — никогда до |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 15: Преждевременный module collapse

| Атрибут | Значение |
|---------|---------|
| **Риск** | В процессе extraction предлагается объединить `platform-tracing-core` и `platform-tracing-otel-extension` или другие модули — нарушение CL isolation |
| **Почему рискованно** | Agent-side код и Application-side код оказываются в одном artifact → классы из Application CL загружаются в Agent CL или наоборот → `ClassCastException` при runtime |
| **Затронутые модули** | Любые два модуля из Internal Runtime группы |
| **Затронутые классы** | Все |
| **Существующие защиты** | Инвентарь явно запрещает collapse в волне 1: `DONOTCOLLAPSENOW`; отдельные `settings.gradle` записи |
| **Отсутствующие защиты** | Нет автоматизированного теста что `agentExtensionJar` не содержит Spring классов; нет теста что app jar не содержит OTel agent extension SPI |
| **Рекомендуемый PR** | PR-1 (dependency guardrails) |
| **Mitigation** | Явное правило: module collapse рассматривается только в PR-13 review (deferred simplification); ArchUnit: `otel-extension` не зависит от Spring; `autoconfigure` не зависит от `otel-extension` в main scope |
| **Требует review** | Security review: нет; SRE review: **да** — ClassLoader topology is ops concern |

---

### Сводная таблица рисков

| # | Риск | Severity | PR | Security | SRE |
|---|------|----------|----|----------|-----|
| 1 | Потеря sampling семантики | **CRITICAL** | PR-5, PR-6 | нет | **да** |
| 2 | Потеря PII scrubbing | **CRITICAL** | PR-5, PR-7 | **да** | **да** |
| 3 | Потеря mandatory span атрибутов | HIGH | PR-5, PR-8 | нет | **да** |
| 4 | Поломка export safety | HIGH | PR-0, PR-12 | нет | **да** |
| 5 | Поломка Spring property binding | HIGH | PR-0, PR-10 | нет | **да** |
| 6 | Поломка WebMVC поведения | HIGH | PR-1, CI | нет | нет |
| 7 | Поломка WebFlux поведения | HIGH | PR-0, CI | нет | нет |
| 8 | Поломка starter experience | MEDIUM | PR-1 | нет | нет |
| 9 | Поломка JMX runtime updates | HIGH | PR-2, PR-3 | нет | **да** |
| 10 | Поломка RefreshScope/RuntimeConfigApplier | MEDIUM | PR-10 | нет | **да** |
| 11 | Actuator MUTATION exposure в prod | **CRITICAL** | PR-11 | **да** | **да** |
| 12 | OTel типы в pure core | MEDIUM | PR-4 | нет | нет |
| 13 | Spring типы в pure core | MEDIUM | PR-4 | нет | нет |
| 14 | Потеря benchmark comparability | MEDIUM | PR-0 | нет | нет |
| 15 | Преждевременный module collapse | HIGH | PR-1, PR-13 | нет | **да** |

---

```text
Pass 2 completed.

Generated sections:
  - 8. Desired State Configuration Layer migration strategy
  - 9. ClassLoader and JMX migration strategy
  - 10. High-risk migration areas

Sections 11–13 intentionally not generated in this pass.
```

---

*Документ сгенерирован на основе: `platform-tracing-current-codebase-inventory.md` (snapshot 2026-06-11). Целевая архитектура — Clean Core Hybrid. PR sequence: PR-0 through PR-13 per approved roadmap. Не содержит architecture review, не предлагает module collapse или big-bang rewrite.*
